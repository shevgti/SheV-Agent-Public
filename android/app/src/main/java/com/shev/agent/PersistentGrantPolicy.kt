package com.shev.agent

/**
 * Узкое постоянное разрешение пользователя.
 *
 * Глобальные grants намеренно не поддерживаются: scope должен указывать
 * конкретный проект, путь или ресурс. Критические возможности нельзя
 * разрешить постоянным grant.
 */
enum class GrantScopeType { PROJECT, PATH, RESOURCE }

data class ScopedCapabilityGrant(
    val grantId: String,
    val capability: AgentCapability,
    val scopeType: GrantScopeType,
    val scopeValue: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long?,
    val revokedAtMillis: Long? = null
)

enum class GrantValidationError {
    BLANK_ID,
    BLANK_SCOPE,
    GLOBAL_SCOPE_NOT_ALLOWED,
    INVALID_TIMESTAMP,
    EXPIRED_AT_CREATION,
    REVOCATION_BEFORE_CREATION,
    CRITICAL_CAPABILITY_NOT_PERSISTENT,
    INVALID_PATH_SCOPE
}

enum class PersistentGrantDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY_CRITICAL_PERSISTENT_GRANT
}

object PersistentGrantPolicy {
    fun validateForCreation(
        grant: ScopedCapabilityGrant,
        nowMillis: Long
    ): GrantValidationError? {
        if (grant.grantId.isBlank()) return GrantValidationError.BLANK_ID
        if (grant.scopeValue.isBlank()) return GrantValidationError.BLANK_SCOPE
        if (grant.scopeValue == "*" || grant.scopeValue == "GLOBAL") {
            return GrantValidationError.GLOBAL_SCOPE_NOT_ALLOWED
        }
        if (grant.scopeType == GrantScopeType.PATH && !isSafeAbsolutePath(grant.scopeValue)) {
            return GrantValidationError.INVALID_PATH_SCOPE
        }
        if (grant.createdAtMillis < 0 ||
            (grant.expiresAtMillis != null && grant.expiresAtMillis <= grant.createdAtMillis)
        ) return GrantValidationError.INVALID_TIMESTAMP
        if (grant.expiresAtMillis != null && grant.expiresAtMillis <= nowMillis) {
            return GrantValidationError.EXPIRED_AT_CREATION
        }
        if (grant.revokedAtMillis != null && grant.revokedAtMillis < grant.createdAtMillis) {
            return GrantValidationError.REVOCATION_BEFORE_CREATION
        }
        if (grant.capability.risk == CapabilityRisk.CRITICAL) {
            return GrantValidationError.CRITICAL_CAPABILITY_NOT_PERSISTENT
        }
        return null
    }

    fun decide(
        capability: AgentCapability,
        target: String,
        grants: Collection<ScopedCapabilityGrant>,
        nowMillis: Long
    ): PersistentGrantDecision {
        if (capability.risk == CapabilityRisk.CRITICAL) {
            return PersistentGrantDecision.DENY_CRITICAL_PERSISTENT_GRANT
        }
        if (target.isBlank()) return PersistentGrantDecision.REQUIRE_APPROVAL

        val matching = grants.any { grant ->
            validateForCreation(grant, nowMillis) == null &&
                grant.capability == capability &&
                grant.revokedAtMillis == null &&
                grant.createdAtMillis <= nowMillis &&
                (grant.expiresAtMillis == null || nowMillis < grant.expiresAtMillis) &&
                (grant.scopeType != GrantScopeType.PATH || isSafeAbsolutePath(target)) &&
                scopeMatches(grant, target)
        }
        return if (matching) PersistentGrantDecision.ALLOW
        else PersistentGrantDecision.REQUIRE_APPROVAL
    }

    private fun scopeMatches(grant: ScopedCapabilityGrant, target: String): Boolean {
        val scope = grant.scopeValue.trimEnd('/')
        return when (grant.scopeType) {
            GrantScopeType.PROJECT,
            GrantScopeType.RESOURCE -> target == scope
            GrantScopeType.PATH -> {
                val canonicalScope = scope.trimEnd('/')
                val canonicalTarget = target.trimEnd('/')
                canonicalTarget == canonicalScope || canonicalTarget.startsWith("$canonicalScope/")
            }
        }
    }

    /**
     * Lexical validation only. File adapters must still resolve canonical paths and
     * reject symlinks escaping the granted directory before opening or mutating files.
     */
    private fun isSafeAbsolutePath(value: String): Boolean {
        if (!value.startsWith("/") || value.contains('\\')) return false
        if (value.any { it.code < 0x20 || it.code == 0x7f }) return false
        val segments = value.split('/').drop(1).dropLastWhile { it.isEmpty() }
        if (segments.isEmpty()) return false // Never grant the filesystem root.
        return segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
