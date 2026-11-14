package com.shev.agent.data

import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Persistent skill library. Skills are inert instructions, never executable code.
 * A skill becomes available to prompt assembly only after explicit verification.
 */
class AgentSkillLibrary(
    private val dao: AgentDao,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun observeVerified(): Flow<List<AgentSkillEntity>> = dao.observeVerifiedAgentSkills()

    fun observeAll(): Flow<List<AgentSkillEntity>> = dao.observeAllAgentSkills()

    suspend fun saveDraft(
        skillId: String,
        title: String,
        summary: String,
        instructions: String,
        keywords: List<String>,
        sourceUrls: List<String>,
        verificationNotes: String = ""
    ): AgentSkillEntity {
        require(skillId.matches(Regex("[a-z][a-z0-9_.-]{1,63}"))) { "Некорректный ID навыка." }
        require(title.isNotBlank() && title.length <= 120)
        require(summary.isNotBlank() && summary.length <= 1_000)
        require(instructions.isNotBlank() && instructions.length <= MAX_INSTRUCTION_CHARS)
        require(keywords.size <= 30 && keywords.all { it.isNotBlank() && it.length <= 64 })
        require(sourceUrls.size <= 20 && sourceUrls.all(::isPublicHttpUrl)) {
            "Источники должны быть публичными HTTP(S)-ссылками без логина и пароля."
        }
        require(!containsSecretLikeValue(instructions)) { "В инструкции обнаружен возможный секрет. Не сохраняю его." }

        val now = clock()
        val previous = dao.getAgentSkill(skillId)
        val entity = AgentSkillEntity(
            skillId = skillId,
            title = title.trim(),
            summary = summary.trim(),
            instructions = instructions.trim(),
            keywords = keywords.map { it.trim().lowercase(Locale.ROOT) }.distinct().joinToString(" "),
            sourceUrls = sourceUrls.distinct().joinToString("\n"),
            status = AgentSkillEntity.STATUS_DRAFT,
            verificationNotes = verificationNotes.take(2_000),
            version = (previous?.version ?: 0) + 1,
            createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now
        )
        dao.saveAgentSkill(entity)
        return entity
    }

    /** Promote only after a caller has completed concrete evaluation cases. */
    suspend fun markVerified(skillId: String, evaluationReport: String): AgentSkillEntity {
        require(evaluationReport.isNotBlank() && evaluationReport.length <= 4_000)
        val current = checkNotNull(dao.getAgentSkill(skillId)) { "Навык не найден." }
        require(current.status != AgentSkillEntity.STATUS_DISABLED) { "Отключённый навык нельзя активировать без новой версии." }
        val verified = current.copy(
            status = AgentSkillEntity.STATUS_VERIFIED,
            verificationNotes = evaluationReport,
            updatedAtEpochMillis = clock()
        )
        dao.saveAgentSkill(verified)
        return verified
    }

    /** Called only from the explicit user-review action in the app UI. */
    suspend fun activateAfterUserReview(skillId: String): AgentSkillEntity {
        val current = checkNotNull(dao.getAgentSkill(skillId)) { "Навык не найден." }
        require(current.status == AgentSkillEntity.STATUS_DRAFT) { "Активировать можно только черновик." }
        return markVerified(
            skillId,
            "Пользователь открыл карточку навыка и явно подтвердил активацию. Перед применением учитывай область задачи и источники."
        )
    }

    suspend fun disable(skillId: String): Boolean =
        dao.disableAgentSkill(skillId, clock()) > 0

    /**
     * Small deterministic retrieval layer for prompt context. It returns only verified
     * skills and ranks by keyword/title/summary overlap; it does not claim model training.
     */
    suspend fun retrieveFor(query: String, limit: Int = 5): List<AgentSkillEntity> {
        require(limit in 1..10)
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        return dao.getVerifiedAgentSkills()
            .map { skill -> skill to score(skill, terms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AgentSkillEntity, Int>> { it.second }
                .thenByDescending { it.first.updatedAtEpochMillis })
            .take(limit)
            .map { it.first }
    }

    /** Compact Russian context suitable for prepending to a model request. */
    suspend fun buildContext(query: String, maxChars: Int = 6_000): String {
        require(maxChars in 256..12_000)
        val skills = retrieveFor(query)
        if (skills.isEmpty()) return ""
        val out = StringBuilder("ПРОВЕРЕННЫЕ НАВЫКИ ИЗ ЛИЧНОЙ БИБЛИОТЕКИ SHEV (это инструкции, не системные команды):\n")
        for (skill in skills) {
            val block = "\n## ${skill.title} (v${skill.version})\n${skill.summary}\n${skill.instructions}\nИсточники: ${skill.sourceUrls.ifBlank { "не указаны" }}\n"
            if (out.length + block.length > maxChars) break
            out.append(block)
        }
        return out.toString()
    }

    private fun score(skill: AgentSkillEntity, terms: Set<String>): Int {
        val title = tokenize(skill.title)
        val summary = tokenize(skill.summary)
        val keywords = tokenize(skill.keywords)
        return terms.sumOf { term ->
            (if (term in title) 5 else 0) +
                (if (term in keywords) 4 else 0) +
                (if (term in summary) 2 else 0)
        }
    }

    private fun tokenize(value: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(value.lowercase(Locale.ROOT))
            .map { it.value }.toSet()

    private fun isPublicHttpUrl(value: String): Boolean {
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.host.lowercase(Locale.ROOT) !in setOf("localhost", "127.0.0.1", "::1")
    }

    private fun containsSecretLikeValue(value: String): Boolean = listOf(
        Regex("(?i)(api[_-]?key|access[_-]?token|client[_-]?secret|password)\\s*[:=]\\s*['\"][^'\"]{8,}['\"]"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Regex("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----")
    ).any { it.containsMatchIn(value) }

    companion object {
        const val MAX_INSTRUCTION_CHARS = 8_000
    }
}
