package com.shev.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentGrantPolicyTest {
    private fun grant(
        capability: AgentCapability = AgentCapability.WRITE_PROJECT_FILES,
        scopeType: GrantScopeType = GrantScopeType.PROJECT,
        scopeValue: String = "repo:SheV-Memory",
        expiresAtMillis: Long? = 20_000,
        revokedAtMillis: Long? = null
    ) = ScopedCapabilityGrant(
        grantId = "grant-1",
        capability = capability,
        scopeType = scopeType,
        scopeValue = scopeValue,
        createdAtMillis = 1_000,
        expiresAtMillis = expiresAtMillis,
        revokedAtMillis = revokedAtMillis
    )

    @Test fun exactProjectGrantAllowsOnlyItsCapabilityAndProject() {
        assertEquals(
            PersistentGrantDecision.ALLOW,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "repo:SheV-Memory",
                listOf(grant()), 5_000
            )
        )
        assertEquals(
            PersistentGrantDecision.REQUIRE_APPROVAL,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "repo:Other",
                listOf(grant()), 5_000
            )
        )
        assertEquals(
            PersistentGrantDecision.REQUIRE_APPROVAL,
            PersistentGrantPolicy.decide(
                AgentCapability.READ_REMOTE_DATA, "repo:SheV-Memory",
                listOf(grant()), 5_000
            )
        )
    }

    @Test fun pathGrantUsesDirectoryBoundaryNotStringPrefix() {
        val pathGrant = grant(scopeType = GrantScopeType.PATH, scopeValue = "/workspace/app")
        assertEquals(
            PersistentGrantDecision.ALLOW,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "/workspace/app/src/Main.kt",
                listOf(pathGrant), 5_000
            )
        )
        assertEquals(
            PersistentGrantDecision.REQUIRE_APPROVAL,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "/workspace/application/secret.txt",
                listOf(pathGrant), 5_000
            )
        )
    }

    @Test fun expiredAndRevokedGrantsDoNotAuthorize() {
        assertEquals(
            PersistentGrantDecision.REQUIRE_APPROVAL,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "repo:SheV-Memory",
                listOf(grant(expiresAtMillis = 5_000)), 5_000
            )
        )
        assertEquals(
            PersistentGrantDecision.REQUIRE_APPROVAL,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "repo:SheV-Memory",
                listOf(grant(revokedAtMillis = 4_000)), 5_000
            )
        )
    }

    @Test fun criticalActionsNeverUsePersistentGrant() {
        assertEquals(
            PersistentGrantDecision.DENY_CRITICAL_PERSISTENT_GRANT,
            PersistentGrantPolicy.decide(
                AgentCapability.DELETE_OR_OVERWRITE_DATA, "repo:SheV-Memory",
                listOf(grant(AgentCapability.DELETE_OR_OVERWRITE_DATA)), 5_000
            )
        )
        assertEquals(
            GrantValidationError.CRITICAL_CAPABILITY_NOT_PERSISTENT,
            PersistentGrantPolicy.validateForCreation(
                grant(AgentCapability.SPEND_MONEY), 2_000
            )
        )
    }

    @Test fun globalAndInvalidScopesAreRejectedAtCreation() {
        assertEquals(
            GrantValidationError.GLOBAL_SCOPE_NOT_ALLOWED,
            PersistentGrantPolicy.validateForCreation(
                grant(scopeValue = "*"), 2_000
            )
        )
        assertEquals(
            GrantValidationError.BLANK_SCOPE,
            PersistentGrantPolicy.validateForCreation(
                grant(scopeValue = " "), 2_000
            )
        )
        assertEquals(
            GrantValidationError.EXPIRED_AT_CREATION,
            PersistentGrantPolicy.validateForCreation(
                grant(expiresAtMillis = 1_500), 2_000
            )
        )
    }

    @Test fun traversalTargetsCannotMatchPathGrants() {
        assertEquals(
            PersistentGrantDecision.REQUIRE_APPROVAL,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "/workspace/app/../secrets",
                listOf(grant(scopeType = GrantScopeType.PATH, scopeValue = "/workspace/app")),
                5_000
            )
        )
    }

    @Test fun pathGrantScopesMustBeAbsoluteCanonicalNonRootPaths() {
        val invalidScopes = listOf(
            "workspace/app",
            "/",
            "/workspace/../secrets",
            "/workspace/./app",
            "/workspace//app",
            "/workspace\\app",
            "/workspace/\u0000app"
        )
        invalidScopes.forEach { scope ->
            assertEquals(
                "scope should be rejected: $scope",
                GrantValidationError.INVALID_PATH_SCOPE,
                PersistentGrantPolicy.validateForCreation(
                    grant(scopeType = GrantScopeType.PATH, scopeValue = scope),
                    2_000
                )
            )
        }
    }

    @Test fun pathGrantRejectsNonCanonicalAndControlCharacterTargets() {
        val pathGrant = grant(scopeType = GrantScopeType.PATH, scopeValue = "/workspace/app")
        listOf(
            "/workspace/app/../secrets",
            "/workspace//app/file",
            "/workspace/app/./file",
            "/workspace/app/\\secret",
            "/workspace/app/\u0000secret",
            "workspace/app/file"
        ).forEach { target ->
            assertEquals(
                "target should require approval: $target",
                PersistentGrantDecision.REQUIRE_APPROVAL,
                PersistentGrantPolicy.decide(
                    AgentCapability.WRITE_PROJECT_FILES, target, listOf(pathGrant), 5_000
                )
            )
        }
    }

    @Test fun pathGrantAllowsTrailingSlashWithoutWideningDirectoryBoundary() {
        val pathGrant = grant(scopeType = GrantScopeType.PATH, scopeValue = "/workspace/app/")
        assertEquals(
            PersistentGrantDecision.ALLOW,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "/workspace/app/src/", listOf(pathGrant), 5_000
            )
        )
        assertEquals(
            PersistentGrantDecision.REQUIRE_APPROVAL,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES, "/workspace/application/secret", listOf(pathGrant), 5_000
            )
        )
    }

    @Test fun unrelatedPathGrantDoesNotBlockMatchingProjectGrant() {
        val projectGrant = grant(scopeValue = "repo:SheV-Memory")
        val unrelatedPathGrant = grant(
            scopeType = GrantScopeType.PATH,
            scopeValue = "/workspace/app"
        )
        assertEquals(
            PersistentGrantDecision.ALLOW,
            PersistentGrantPolicy.decide(
                AgentCapability.WRITE_PROJECT_FILES,
                "repo:SheV-Memory",
                listOf(projectGrant, unrelatedPathGrant),
                5_000
            )
        )
    }

    @Test fun nonExpiredGrantIsValidAtCreation() {
        assertEquals(
            null,
            PersistentGrantPolicy.validateForCreation(grant(), 2_000)
        )
    }
}
