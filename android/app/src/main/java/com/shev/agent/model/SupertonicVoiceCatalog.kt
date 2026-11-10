package com.shev.agent.model

enum class SupertonicVoiceGender { FEMALE, MALE }

data class SupertonicSpeaker(val id: Int, val title: String, val gender: SupertonicVoiceGender)

data class SupertonicSpeechStyle(
    val id: String,
    val title: String,
    val description: String,
    val expressionTag: String?
)

object SupertonicVoiceCatalog {
    // Supertonic-3 voice-style assets are F1-F5 for sid 0-4 and M1-M5 for sid 5-9.
    val speakers = (0 until 10).map { id ->
        if (id < 5) {
            SupertonicSpeaker(id, "Женский голос ${id + 1}", SupertonicVoiceGender.FEMALE)
        } else {
            SupertonicSpeaker(id, "Мужской голос ${id - 4}", SupertonicVoiceGender.MALE)
        }
    }

    // Expression tags are native Supertonic-3 text markers. Their reliability in Russian
    // is lower than in English/Japanese/Korean, so expose them as experimental styles.
    val styles = listOf(
        SupertonicSpeechStyle("natural", "Обычная", "Естественная речь без маркера", null),
        SupertonicSpeechStyle("joyful", "Радостная", "С лёгким смехом", "<laugh>"),
        SupertonicSpeechStyle("surprised", "Удивлённая", "Эмоциональное удивление", "<surprise>"),
        SupertonicSpeechStyle("sad", "Грустная", "Грустная интонация", "<sad>"),
        SupertonicSpeechStyle("angry", "Сердитая", "Более резкая подача", "<angry>"),
        SupertonicSpeechStyle("gentle", "Мягкая", "Мягкий вздох перед фразой", "<sigh>")
    )
}
