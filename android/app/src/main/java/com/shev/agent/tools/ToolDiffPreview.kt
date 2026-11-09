package com.shev.agent.tools

data class ToolDiffLine(val prefix: Char, val text: String)

data class ToolSourceDiff(
    val lines: List<ToolDiffLine>,
    val addedLines: Int,
    val removedLines: Int
)

/**
 * Компактный diff для ревью: показывает удалённые/добавленные строки.
 * Это не алгоритм оптимального diff и не заменяет проверку исходников.
 */
object ToolDiffPreview {
    fun compare(oldContent: String, newContent: String): ToolSourceDiff {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }

        var suffix = 0
        while (
            suffix < oldLines.size - prefix &&
            suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) {
            suffix++
        }

        val removed = oldLines.subList(prefix, oldLines.size - suffix)
        val added = newLines.subList(prefix, newLines.size - suffix)
        val lines = buildList {
            oldLines.take(prefix).forEach { add(ToolDiffLine(' ', it)) }
            removed.forEach { add(ToolDiffLine('-', it)) }
            added.forEach { add(ToolDiffLine('+', it)) }
            if (suffix > 0) newLines.takeLast(suffix).forEach { add(ToolDiffLine(' ', it)) }
        }
        return ToolSourceDiff(lines, added.size, removed.size)
    }
}
