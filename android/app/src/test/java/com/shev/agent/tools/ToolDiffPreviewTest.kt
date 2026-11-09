package com.shev.agent.tools

import org.junit.Assert.*
import org.junit.Test

class ToolDiffPreviewTest {
    @Test fun reportsChangedLinesAndKeepsSharedContext() {
        val diff = ToolDiffPreview.compare("first\nold\nlast", "first\nnew\nlast")
        assertEquals(1, diff.addedLines)
        assertEquals(1, diff.removedLines)
        assertTrue(diff.lines.any { it.prefix == '-' && it.text == "old" })
        assertTrue(diff.lines.any { it.prefix == '+' && it.text == "new" })
        assertTrue(diff.lines.any { it.prefix == ' ' && it.text == "first" })
    }

    @Test fun identicalContentHasNoChanges() {
        val diff = ToolDiffPreview.compare("same", "same")
        assertEquals(0, diff.addedLines)
        assertEquals(0, diff.removedLines)
    }
}
