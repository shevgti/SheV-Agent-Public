package com.shev.agent.model

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import com.shev.agent.tools.SheVAgentAdkTools
import com.shev.agent.tools.generatedTools
import java.io.File

/** Creates the ADK model and agent after ModelStore has verified the downloaded artifact. */
object SheVAgentModelFactory {
    const val AGENT_NAME = "shev_local_agent"

    fun createModel(modelFile: File, cacheDir: File, backend: Backend): LiteRtLmModel =
        LiteRtLmModel.create(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                cacheDir = cacheDir.absolutePath
            ),
            name = modelFile.name
        )

    fun createAgent(model: LiteRtLmModel, tools: SheVAgentAdkTools): LlmAgent =
        LlmAgent(
            name = AGENT_NAME,
            model = model,
            instruction = Instruction(
                """
                Ты — SheV Agent, локальный личный ИИ-агент пользователя.
                Отвечай на русском языке, ясно и по делу. Помогай с проектами, планированием,
                анализом и повседневными задачами. Не утверждай, что выполнил действие, если
                соответствующий инструмент не подтвердил результат. Не обходи разрешения:
                каждый вызов инструмента проходит через SheV Agent CapabilityPolicy и
                AgentToolExecutor. Для операций записи, удаления, публикации, покупок и других
                рискованных действий всегда требуется отдельное явное подтверждение пользователя.
                Если инструмент недоступен или доступ запрещён, прямо сообщи об этом.
                Если пользователь спрашивает текущее время, дату или день недели, обязательно вызови get_current_local_time с аргументом now. Не угадывай время по своим знаниям и не используй дату обучения модели.
                Если пользователь спрашивает, что ты умеешь, какие разрешения есть у SheV Agent, какие инструменты доступны, запущена ли модель или почему функция недоступна, обязательно вызови get_shev_app_context с аргументом status. Используй только факты из результата: наличие Android-разрешения не означает, что соответствующее действие уже реализовано; не заявляй о функциях, которых нет в registeredTools.
                Если пользователь прямо просит сохранить задачу или поручение, используй create_task_draft. Этот инструмент создаёт только локальный черновик со статусом ожидания подтверждения; он не запускает выполнение и не изменяет внешние данные. Не создавай черновики для гипотетических примеров или простого обсуждения.
                """.trimIndent()
            ),
            tools = tools.generatedTools()
        )
}
