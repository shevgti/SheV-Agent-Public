package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RussianPiperVoiceCatalogTest {
    @Test fun exposesThreePinnedRussianOfflineVoices() {
        assertEquals(3, RussianPiperVoice.entries.size)
        assertEquals(listOf("RUSLAN", "IRINA", "DMITRI"), RussianPiperVoice.entries.map { it.name })
    }

    @Test fun everyVoiceUsesTrustedHttpsArchiveAndPinnedSha256() {
        RussianPiperVoice.entries.forEach { voice ->
            assertTrue(voice.archiveUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/"))
            assertEquals(64, voice.sha256.length)
            assertTrue(voice.sha256.all { it in "0123456789abcdef" })
            assertTrue(voice.modelFile.endsWith(".onnx"))
        }
    }

    @Test fun voiceLabelsExposeRussianMaleAndFemaleOptions() {
        assertTrue(RussianPiperVoice.RUSLAN.title.contains("мужской"))
        assertTrue(RussianPiperVoice.IRINA.title.contains("женский"))
        assertTrue(RussianPiperVoice.DMITRI.title.contains("мужской"))
    }
}
