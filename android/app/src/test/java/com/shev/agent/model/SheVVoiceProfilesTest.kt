package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheVVoiceProfilesTest {
    @Test fun exposesFiveFemaleAndFiveMaleProfiles() {
        assertEquals(5, SheVVoiceProfiles.profiles.count { it.gender == SheVVoiceGender.FEMALE })
        assertEquals(5, SheVVoiceProfiles.profiles.count { it.gender == SheVVoiceGender.MALE })
        assertEquals(10, SheVVoiceProfiles.profiles.map { it.id }.distinct().size)
    }

    @Test fun profilesHaveDistinctPitchAndRateCombinations() {
        assertEquals(
            SheVVoiceProfiles.profiles.size,
            SheVVoiceProfiles.profiles.map { it.pitch to it.rate }.distinct().size
        )
    }

    @Test fun exposesMultipleDeliveryEmotions() {
        assertTrue(SheVVoiceProfiles.emotions.size >= 8)
        assertEquals(
            SheVVoiceProfiles.emotions.size,
            SheVVoiceProfiles.emotions.map { it.id }.distinct().size
        )
    }

    @Test fun allPitchAndRateValuesStayWithinAndroidTtsBounds() {
        (SheVVoiceProfiles.profiles.map { it.pitch to it.rate } +
            SheVVoiceProfiles.emotions.map { it.pitchMultiplier to it.rateMultiplier })
            .forEach { (pitch, rate) ->
                assertTrue(pitch in 0.5f..2.0f)
                assertTrue(rate in 0.5f..2.0f)
            }
    }
}
