package com.shev.agent.model

import android.content.Context
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.ai.edge.litertlm.Backend
import com.shev.agent.tools.SheVAgentAdkTools
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Creates the local ADK conversation before the runner receives its first message. */
internal object SheVAgentSession {
    const val APP_NAME = "SheVAgent"
    const val USER_ID = "local-user"
    const val SESSION_ID = "main-session"
    const val CREATOR_SESSION_ID = "creator-session"

    suspend fun initialize(sessionService: InMemorySessionService) {
        sessionService.createSession(SessionKey(APP_NAME, USER_ID, SESSION_ID))
        sessionService.createSession(SessionKey(APP_NAME, USER_ID, CREATOR_SESSION_ID))
    }
}

/** Owns the native model and ADK runner. */
class SheVAgentRuntime private constructor(
    private val model: LiteRtLmModel,
    private val runner: InMemoryRunner,
    private val creatorRunner: InMemoryRunner,
    val backendName: String
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun run(text: String): Flow<Event> {
        check(!closed.get()) { "SheV Agent runtime is closed." }
        require(text.isNotBlank())
        return runner.runAsync(
            userId = SheVAgentSession.USER_ID,
            sessionId = SheVAgentSession.SESSION_ID,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
            runConfig = RunConfig(maxLlmCalls = MAX_LLM_CALLS)
        )
    }

    /** Tool-free creator runner: generated manifests cannot invoke host actions. */
    fun runCreator(text: String): Flow<Event> {
        check(!closed.get()) { "SheV Agent runtime is closed." }
        require(text.isNotBlank())
        return creatorRunner.runAsync(
            userId = SheVAgentSession.USER_ID,
            sessionId = SheVAgentSession.CREATOR_SESSION_ID,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
            runConfig = RunConfig(maxLlmCalls = MAX_CREATOR_LLM_CALLS)
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            runner.close()
        } finally {
            try { creatorRunner.close() } finally { model.close() }
        }
    }

    companion object {
        private const val MAX_LLM_CALLS = 8
        private const val MAX_CREATOR_LLM_CALLS = 4

        /**
         * Prefer the Adreno GPU. If native initialization fails, retry once on CPU.
         * A successful initialize is not a guarantee that the first inference will succeed;
         * runtime diagnostics must still report any first-token/backend failure.
         */
        suspend fun load(
            context: Context,
            modelStore: ModelStore,
            tools: SheVAgentAdkTools
        ): SheVAgentRuntime = withContext(Dispatchers.IO) {
            val modelFile = checkNotNull(modelStore.findVerifiedModel()) {
                "The local model is missing or failed SHA-256 verification. Download it first."
            }
            val attempts = listOf(
                "GPU" to Backend.GPU(),
                "CPU" to Backend.CPU()
            )
            var lastFailure: Throwable? = null

            for ((backendName, backend) in attempts) {
                var model: LiteRtLmModel? = null
                var runner: InMemoryRunner? = null
                var creatorRunner: InMemoryRunner? = null
                var initialized = false
                try {
                    val activeModel = SheVAgentModelFactory.createModel(modelFile, context.cacheDir, backend)
                    model = activeModel
                    activeModel.engine.initialize()
                    initialized = true
                    val enabledRoles = context.getSharedPreferences("shev_agent_team", Context.MODE_PRIVATE)
                        .getStringSet("enabled_roles", null)?.toSet()
                        ?: setOf("shev_researcher", "shev_planner", "shev_engineer", "shev_reviewer")
                    val enabledSkills = context.getSharedPreferences("shev_agent_team", Context.MODE_PRIVATE)
                        .getStringSet("enabled_skills", null)?.toSet()
                        ?: SheVAgentModelFactory.DEFAULT_SKILLS
                    val agent = SheVAgentModelFactory.createAgent(activeModel, tools, enabledRoles, enabledSkills)
                    val sessionService = InMemorySessionService()
                    SheVAgentSession.initialize(sessionService)
                    val activeRunner = InMemoryRunner(
                        agent = agent,
                        appName = SheVAgentSession.APP_NAME,
                        sessionService = sessionService
                    )
                    runner = activeRunner
                    val activeCreatorRunner = InMemoryRunner(
                        agent = SheVAgentModelFactory.createCreatorAgent(activeModel),
                        appName = SheVAgentSession.APP_NAME,
                        sessionService = sessionService
                    )
                    creatorRunner = activeCreatorRunner
                    return@withContext SheVAgentRuntime(activeModel, activeRunner, activeCreatorRunner, backendName)
                } catch (cancelled: CancellationException) {
                    try { runner?.close() } catch (_: Throwable) { /* Preserve cancellation. */ }
                    try { creatorRunner?.close() } catch (_: Throwable) { /* Preserve cancellation. */ }
                    if (initialized) {
                        try {
                            model?.close()
                        } catch (_: Throwable) {
                            // Preserve cancellation.
                        }
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    lastFailure = error
                    try { runner?.close() } catch (_: Throwable) { /* Preserve backend error. */ }
                    try { creatorRunner?.close() } catch (_: Throwable) { /* Preserve backend error. */ }
                    // LiteRT-LM requires close() only after initialize() completed successfully.
                    if (initialized) {
                        try {
                            model?.close()
                        } catch (_: Throwable) {
                            // Preserve the backend initialization error.
                        }
                    }
                }
            }
            throw IllegalStateException(
                "Не удалось запустить модель на GPU и CPU. Проверь модель и свободную память.",
                lastFailure
            )
        }
    }
}
