package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianVoiceCatalogTest {
    @Test fun classifiesGoogleRussianFemaleVoiceIds() {
        assertEquals(RussianVoiceGender.FEMALE, RussianVoiceCatalog.classify("ru-ru-x-ruf-network"))
        assertEquals(RussianVoiceGender.FEMALE, RussianVoiceCatalog.classify("ru-RU-x-ruf-local"))
    }

    @Test fun classifiesGoogleRussianMaleVoiceIds() {
        assertEquals(RussianVoiceGender.MALE, RussianVoiceCatalog.classify("ru-ru-x-rum-network"))
        assertEquals(RussianVoiceGender.MALE, RussianVoiceCatalog.classify("ru-RU-x-rum-local"))
    }

    @Test fun supportsExplicitProviderGenderTokens() {
        assertEquals(RussianVoiceGender.FEMALE, RussianVoiceCatalog.classify("Russian-Female-2"))
        assertEquals(RussianVoiceGender.MALE, RussianVoiceCatalog.classify("Russian-Male-3"))
    }

    @Test fun doesNotGuessGenderFromUnknownIds() {
        assertEquals(RussianVoiceGender.UNKNOWN, RussianVoiceCatalog.classify("ru-RU-standard"))
        assertEquals(RussianVoiceGender.UNKNOWN, RussianVoiceCatalog.classify("voice-ru-001"))
    }
}
