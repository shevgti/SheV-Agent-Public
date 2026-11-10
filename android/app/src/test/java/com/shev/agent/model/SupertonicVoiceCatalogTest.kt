package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupertonicVoiceCatalogTest {
    @Test fun exposesFiveFemaleAndFiveMaleSpeakerStyles() {
        assertEquals(5, SupertonicVoiceCatalog.speakers.count { it.gender == SupertonicVoiceGender.FEMALE })
        assertEquals(5, SupertonicVoiceCatalog.speakers.count { it.gender == SupertonicVoiceGender.MALE })
        assertEquals((0..9).toList(), SupertonicVoiceCatalog.speakers.map { it.id })
    }

    @Test fun speakerLabelsMatchSupertonicStyleNames() {
        assertEquals("Женский голос 1", SupertonicVoiceCatalog.speakers[0].title)
        assertEquals("Женский голос 5", SupertonicVoiceCatalog.speakers[4].title)
        assertEquals("Мужской голос 1", SupertonicVoiceCatalog.speakers[5].title)
        assertEquals("Мужской голос 5", SupertonicVoiceCatalog.speakers[9].title)
    }

    @Test fun exposesUniqueExpressionStylesAndOnlySupportedTags() {
        assertTrue(SupertonicVoiceCatalog.styles.size >= 6)
        assertEquals(
            SupertonicVoiceCatalog.styles.size,
            SupertonicVoiceCatalog.styles.map { it.id }.distinct().size
        )
        SupertonicVoiceCatalog.styles.mapNotNull { it.expressionTag }.forEach {
            assertTrue(it in SupertonicRussianTts.SUPPORTED_EXPRESSION_TAGS)
        }
    }
}
