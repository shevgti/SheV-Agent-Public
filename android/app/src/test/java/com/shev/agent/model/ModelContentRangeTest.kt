package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelContentRangeTest {
    @Test
    fun parsesCompleteRange() {
        assertEquals(ModelContentRange(10, 19, 100), parseModelContentRange("bytes 10-19/100"))
    }

    @Test
    fun parsesUnknownTotal() {
        assertEquals(ModelContentRange(10, 19, null), parseModelContentRange("bytes 10-19/*"))
    }

    @Test
    fun rejectsMalformedAndOutOfBoundsRanges() {
        assertNull(parseModelContentRange("bytes 10-19/19"))
        assertNull(parseModelContentRange("bytes 19-10/100"))
        assertNull(parseModelContentRange("items 10-19/100"))
        assertNull(parseModelContentRange(null))
    }


    @Test
    fun modelDownloadSizeLimitRejectsOversizedOrInvalidLengths() {
        assertEquals(true, ModelDownloadLimits.allows(2_580_000_000L))
        assertEquals(true, ModelDownloadLimits.allows(ModelDownloadLimits.MAX_BYTES))
        assertEquals(false, ModelDownloadLimits.allows(ModelDownloadLimits.MAX_BYTES + 1L))
        assertEquals(false, ModelDownloadLimits.allows(0L))
        assertEquals(false, ModelDownloadLimits.allows(-1L))
    }

}
