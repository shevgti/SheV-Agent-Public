package com.shev.agent

/**
 * Универсальные возможности SheV Agent.
 * Наличие возможности в коде само по себе не выдаёт системное или сетевое разрешение.
 */
enum class AgentCapability(
    val risk: CapabilityRisk,
    val title: String,
    val description: String
) {
    READ_LOCAL_DATA(CapabilityRisk.LOW, "Чтение локальных данных", "Читать данные, к которым пользователь уже предоставил доступ."),
    ANALYZE_DATA(CapabilityRisk.LOW, "Анализ информации", "Анализировать доступные пользователю данные без их изменения."),
    CREATE_DRAFT(CapabilityRisk.LOW, "Создание черновиков", "Создавать локальные черновики документов и сообщений."),
    RUN_LOCAL_TESTS(CapabilityRisk.LOW, "Локальные тесты", "Запускать тесты в разрешённом рабочем каталоге."),
    READ_REMOTE_DATA(CapabilityRisk.MEDIUM, "Чтение из сети", "Получать данные из явно подключённого внешнего сервиса."),
    WRITE_PROJECT_FILES(CapabilityRisk.HIGH, "Изменение файлов", "Создавать или изменять файлы проекта."),
    INSTALL_OR_UPDATE_SOFTWARE(CapabilityRisk.HIGH, "Установка и обновление", "Устанавливать или обновлять программное обеспечение."),
    SEND_MESSAGE_OR_PUBLISH(CapabilityRisk.CRITICAL, "Отправка и публикация", "Отправлять сообщения или публиковать материалы от имени пользователя."),
    SPEND_MONEY(CapabilityRisk.CRITICAL, "Финансовые действия", "Совершать платежи или другие операции с расходованием средств."),
    DELETE_OR_OVERWRITE_DATA(CapabilityRisk.CRITICAL, "Удаление и перезапись", "Удалять или необратимо перезаписывать данные."),
    CHANGE_ACCESS_OR_SECURITY(CapabilityRisk.CRITICAL, "Изменение доступа и безопасности", "Менять права доступа, учётные данные или настройки безопасности."),
    CONTROL_DEVICE_SETTINGS(CapabilityRisk.CRITICAL, "Управление устройством", "Изменять системные настройки устройства.")
}

enum class CapabilityRisk { LOW, MEDIUM, HIGH, CRITICAL }

enum class ApprovalDecision { ALLOW, REQUIRE_APPROVAL, DENY }

object CapabilityPolicy {
    /**
     * Разрешение capability и подтверждение конкретного действия — разные условия.
     * Общий режим автономности не может отменить подтверждение критического действия.
     */
    fun decide(
        capability: AgentCapability,
        explicitlyApprovedForThisAction: Boolean,
        capabilityGrantedByUser: Boolean
    ): ApprovalDecision {
        if (!capabilityGrantedByUser) return ApprovalDecision.DENY
        return when (capability.risk) {
            CapabilityRisk.LOW -> ApprovalDecision.ALLOW
            CapabilityRisk.MEDIUM,
            CapabilityRisk.HIGH,
            CapabilityRisk.CRITICAL ->
                if (explicitlyApprovedForThisAction) ApprovalDecision.ALLOW
                else ApprovalDecision.REQUIRE_APPROVAL
        }
    }
}
