package com.shev.agent.model

/**
 * Android TextToSpeech exposes provider-specific voice IDs rather than a
 * portable gender field. Google TTS Russian IDs commonly encode voice
 * families as "ruf" (female) and "rum" (male); generic providers may use
 * explicit female/male tokens. Unknown IDs are deliberately not guessed.
 */
enum class RussianVoiceGender {
    FEMALE,
    MALE,
    UNKNOWN
}

object RussianVoiceCatalog {
    fun classify(voiceId: String): RussianVoiceGender {
        val id = voiceId.lowercase()
        val female = listOf("female", "fem", "x-ruf", "_ruf", "-ruf", "voice-f")
        val male = listOf("male", "masc", "x-rum", "_rum", "-rum", "voice-m")
        return when {
            female.any(id::contains) -> RussianVoiceGender.FEMALE
            male.any(id::contains) -> RussianVoiceGender.MALE
            else -> RussianVoiceGender.UNKNOWN
        }
    }
}
