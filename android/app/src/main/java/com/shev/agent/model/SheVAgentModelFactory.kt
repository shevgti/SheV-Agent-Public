package com.shev.agent.model

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import com.shev.agent.tools.SheVAgentAdkTools
import com.shev.agent.tools.generatedTools
import java.io.File

/**
 * Agent roles share one already-loaded LiteRT-LM model. This avoids loading multiple
 * model copies into phone RAM while still allowing ADK to delegate between specialists.
 */
object SheVAgentModelFactory {
    const val AGENT_NAME = "shev_local_agent"

    private data class Specialist(
        val name: String,
        val description: String,
        val instruction: String
    )

    private val specialists = listOf(
        Specialist(
            name = "shev_researcher",
            description = "Исследует вопрос, выделяет факты, неизвестное и источники. Передаёт координатору краткий результат.",
            instruction = "Ты исследователь команды SheV. Отвечай по-русски. Разбирай запрос на факты, предположения и пробелы. Не выдумывай источники и не заявляй о выполненном поиске, если инструмента поиска не было. Передай координатору краткий структурированный вывод."
        ),
        Specialist(
            name = "shev_planner",
            description = "Разбивает сложные поручения на реалистичные последовательные этапы и зависимости.",
            instruction = "Ты планировщик команды SheV. Отвечай по-русски. Составляй краткий выполнимый план, учитывай зависимости, риски, ограничения телефона и необходимость подтверждения пользователя. Сам не утверждай, что выполнял действия."
        ),
        Specialist(
            name = "shev_engineer",
            description = "Анализирует программную архитектуру, Kotlin/Android, GitHub и предлагает технические изменения.",
            instruction = "Ты инженер-разработчик команды SheV. Отвечай по-русски. Предлагай конкретные решения Kotlin/Android, учитывай совместимость и тестирование. Не заявляй, что изменил репозиторий или собрал APK без подтверждения инструментом."
        ),
        Specialist(
            name = "shev_reviewer",
            description = "Критически проверяет решения, ищет ошибки, риски безопасности и непроверенные утверждения.",
            instruction = "Ты независимый контролёр качества SheV. Отвечай по-русски. Ищи логические ошибки, небезопасные действия, завышенные обещания и непроверенные факты. Дай конкретные замечания и критерии приёмки."
        )
    )

    fun createModel(modelFile: File, cacheDir: File, backend: Backend): LiteRtLmModel =
        LiteRtLmModel.create(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                cacheDir = cacheDir.absolutePath
            ),
            name = modelFile.name
        )

    fun createAgent(model: LiteRtLmModel, tools: SheVAgentAdkTools, enabledSpecialists: Set<String> = specialists.map { it.name }.toSet()): LlmAgent {
        val activeSpecialists = specialists.filter { it.name in enabledSpecialists }
        val team = activeSpecialists.map { specialist ->
            LlmAgent(
                name = specialist.name,
                model = model,
                description = specialist.description,
                instruction = Instruction(specialist.instruction),
                maxSteps = 2
            )
        }

        return LlmAgent(
            name = AGENT_NAME,
            model = model,
            description = "Главный координатор локальной команды AI-агентов SheV.",
            instruction = Instruction(
                """
                Ты — SheV Agent, главный координатор локальной команды ИИ-помощников.
                Отвечай на русском языке, ясно и по делу. У тебя есть специалисты:
                исследователь (shev_researcher), планировщик (shev_planner),
                инженер-разработчик (shev_engineer) и контролёр качества (shev_reviewer).
                Доступные в этой сессии специалисты: ${activeSpecialists.joinToString { it.name }}.
                Не делегируй отключённым специалистам и не пытайся вызвать отсутствующую роль.
                Делегируй им подходящие части сложных задач через передачу управления агенту.
                Не запускай всех специалистов для простого вопроса: выбирай только нужных.
                Для сложного плана попроси планировщика; для программирования — инженера;
                для проверки — контролёра; для исследования — исследователя. После ответа
                специалиста собери единый итог и явно обозначь непроверенные сведения.
                Все роли используют одну локальную модель на устройстве, не загружай и не
                запускай дополнительные копии модели.

                Не утверждай, что выполнил действие, если соответствующий инструмент не подтвердил
                результат. Не обходи разрешения: каждый вызов инструмента проходит через SheV Agent
                CapabilityPolicy и AgentToolExecutor. Для операций записи, удаления, публикации,
                покупок и других рискованных действий всегда требуется отдельное явное подтверждение
                пользователя. Специалисты не получают дополнительных прав и не могут обходить эти
                ограничения. Если инструмент недоступен или доступ запрещён, прямо сообщи об этом.

                Если пользователь спрашивает текущее время, дату или день недели, обязательно вызови
                get_current_local_time с аргументом now. Не угадывай время по своим знаниям и не
                используй дату обучения модели.
                Если пользователь прямо просит сохранить задачу или поручение, используй
                create_task_draft. Этот инструмент создаёт только локальный черновик со статусом
                ожидания подтверждения; он не запускает выполнение и не изменяет внешние данные.
                Не создавай черновики для гипотетических примеров или простого обсуждения.
                """.trimIndent()
            ),
            subAgents = team,
            tools = tools.generatedTools(),
            maxSteps = 8
        )
    }
}
