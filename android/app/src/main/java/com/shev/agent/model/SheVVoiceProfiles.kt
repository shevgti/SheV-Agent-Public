package com.shev.agent.model

enum class SheVVoiceGender { FEMALE, MALE }

data class SheVVoiceProfile(
    val id: String,
    val title: String,
    val description: String,
    val gender: SheVVoiceGender,
    /** Android TTS pitch multiplier; this changes delivery, not the underlying speaker timbre. */
    val pitch: Float,
    /** Android TTS speech-rate multiplier. */
    val rate: Float
)

data class SheVVoiceEmotion(
    val id: String,
    val title: String,
    val description: String,
    val pitchMultiplier: Float,
    val rateMultiplier: Float
)

object SheVVoiceProfiles {
    val profiles = listOf(
        SheVVoiceProfile("female_calm", "Мягкая", "Спокойная, плавная подача", SheVVoiceGender.FEMALE, 1.12f, 0.92f),
        SheVVoiceProfile("female_bright", "Энергичная", "Более звонкая и быстрая", SheVVoiceGender.FEMALE, 1.24f, 1.08f),
        SheVVoiceProfile("female_velvet", "Бархатная", "Ниже и медленнее", SheVVoiceGender.FEMALE, 0.98f, 0.88f),
        SheVVoiceProfile("female_warm", "Заботливая", "Тёплая, мягкая интонация", SheVVoiceGender.FEMALE, 1.08f, 0.96f),
        SheVVoiceProfile("female_expressive", "Выразительная", "Подвижная, эмоциональная подача", SheVVoiceGender.FEMALE, 1.18f, 1.04f),
        SheVVoiceProfile("male_confident", "Уверенный", "Более низкая и размеренная подача", SheVVoiceGender.MALE, 0.78f, 0.94f),
        SheVVoiceProfile("male_young", "Молодой", "Лёгкая, быстрая подача", SheVVoiceGender.MALE, 0.91f, 1.08f),
        SheVVoiceProfile("male_deep", "Глубокий", "Самая низкая и медленная подача", SheVVoiceGender.MALE, 0.68f, 0.88f),
        SheVVoiceProfile("male_friendly", "Дружелюбный", "Мягкая, спокойная подача", SheVVoiceGender.MALE, 0.84f, 0.98f),
        SheVVoiceProfile("male_dynamic", "Динамичный", "Более энергичная подача", SheVVoiceGender.MALE, 0.88f, 1.14f)
    )

    val emotions = listOf(
        SheVVoiceEmotion("natural", "Обычная", "Естественная беседа", 1.0f, 1.0f),
        SheVVoiceEmotion("joyful", "Радостная", "Светлее и энергичнее", 1.08f, 1.10f),
        SheVVoiceEmotion("calm", "Спокойная", "Мягче и медленнее", 0.96f, 0.88f),
        SheVVoiceEmotion("empathetic", "Сочувствующая", "Теплее и бережнее", 0.98f, 0.92f),
        SheVVoiceEmotion("serious", "Серьёзная", "Сдержанная и размеренная", 0.92f, 0.90f),
        SheVVoiceEmotion("surprised", "Удивлённая", "Выше и живее", 1.14f, 1.06f),
        SheVVoiceEmotion("inspired", "Воодушевлённая", "Выразительная и быстрая", 1.10f, 1.12f),
        SheVVoiceEmotion("business", "Деловая", "Чёткая и ровная", 0.98f, 1.02f)
    )
}
