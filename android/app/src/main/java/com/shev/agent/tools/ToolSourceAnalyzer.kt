package com.shev.agent.tools

enum class ToolFindingSeverity { WARNING, BLOCKER }

data class ToolSourceFinding(
    val severity: ToolFindingSeverity,
    val ruleId: String,
    val message: String
)

/**
 * Lightweight static preflight only. It is not a compiler, sandbox, or security proof.
 */
object ToolSourceAnalyzer {
    private val blockedPatterns = listOf(
        Regex("""\bRuntime\s*\.\s*getRuntime\s*\(""") to "PROCESS_RUNTIME",
        Regex("""\bProcessBuilder\s*\(""") to "PROCESS_BUILDER",
        Regex("""\bClass\s*\.\s*forName\s*\(""") to "REFLECTION",
        Regex("""\b(ClassLoader|DexClassLoader|PathClassLoader)\b""") to "DYNAMIC_CLASS_LOADING",
        Regex("""\b(System\s*\.\s*loadLibrary|System\s*\.\s*load)\s*\(""") to "NATIVE_CODE_LOADING",
        Regex("""\b(WebView\s*\.\s*addJavascriptInterface|addJavascriptInterface\s*\()""") to "WEBVIEW_BRIDGE",
        Regex("""\b(java\.io\.File|FileInputStream|FileOutputStream)\b""") to "DIRECT_FILE_ACCESS",
        Regex("""\b(java\.net\.|okhttp3\.|ktor\.client)""") to "NETWORK_ACCESS",
        Regex("""\b(android\.Manifest\.permission|requestPermissions\s*\()""") to "PERMISSION_REQUEST"
    )

    fun analyze(source: String): List<ToolSourceFinding> =
        blockedPatterns.mapNotNull { (pattern, id) ->
            if (pattern.containsMatchIn(source)) {
                ToolSourceFinding(
                    ToolFindingSeverity.BLOCKER,
                    id,
                    "Обнаружен API, требующий отдельного адаптера и явного разрешения: $id"
                )
            } else null
        }
}

/** Политика зависимостей по groupId; неизвестные группы требуют ручного рассмотрения. */
object ToolDependencyPolicy {
    private val allowedGroups = setOf(
        "org.jetbrains.kotlin",
        "androidx",
        "com.google.devtools.ksp",
        "junit"
    )

    fun isAllowed(coordinate: String): Boolean {
        val group = coordinate.substringBefore(':').trim()
        return group in allowedGroups || group.startsWith("androidx.")
    }
}
