package com.shev.agent.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseInstallPolicyTest {
    private val installedSigners = setOf("a".repeat(64))

    @Test fun acceptsOnlyMatchingPackageVersionAndSigner() {
        ReleaseInstallPolicy.validate(
            expectedPackageName = "com.shev.agent",
            expectedVersionCode = 34L,
            installedSignerSha256 = installedSigners,
            candidate = ApkIdentity("com.shev.agent", 34L, installedSigners)
        )
    }

    @Test fun rejectsDifferentPackageName() {
        assertThrows(IllegalStateException::class.java) {
            ReleaseInstallPolicy.validate(
                "com.shev.agent", 34L, installedSigners,
                ApkIdentity("com.other.app", 34L, installedSigners)
            )
        }
    }

    @Test fun rejectsDifferentVersionCode() {
        assertThrows(IllegalStateException::class.java) {
            ReleaseInstallPolicy.validate(
                "com.shev.agent", 34L, installedSigners,
                ApkIdentity("com.shev.agent", 33L, installedSigners)
            )
        }
    }

    @Test fun rejectsDifferentSigningCertificate() {
        assertThrows(IllegalStateException::class.java) {
            ReleaseInstallPolicy.validate(
                "com.shev.agent", 34L, installedSigners,
                ApkIdentity("com.shev.agent", 34L, setOf("b".repeat(64)))
            )
        }
    }

    @Test fun rejectsMissingSignerInformation() {
        assertThrows(IllegalStateException::class.java) {
            ReleaseInstallPolicy.validate(
                "com.shev.agent", 34L, emptySet(),
                ApkIdentity("com.shev.agent", 34L, emptySet())
            )
        }
    }
}
