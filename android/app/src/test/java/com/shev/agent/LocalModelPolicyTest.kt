package com.shev.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelPolicyTest {
    @Test fun acceptsOnlyReadyIdentifiedOnDeviceModel() {
        assertTrue(
            LocalModelPolicy.canGenerate(
                LocalModelStatus(LocalModelState.READY, modelId = "model-1", runsOnDevice = true)
            )
        )
    }

    @Test fun rejectsCloudRuntimeEvenWhenMarkedReady() {
        assertFalse(
            LocalModelPolicy.canGenerate(
                LocalModelStatus(LocalModelState.READY, modelId = "remote-model", runsOnDevice = false)
            )
        )
        assertEquals(
            "Облачная модель не считается локальным режимом.",
            LocalModelPolicy.unavailableReason(
                LocalModelStatus(LocalModelState.READY, modelId = "remote-model", runsOnDevice = false)
            )
        )
    }

    @Test fun rejectsReadyStateWithoutModelIdentity() {
        assertFalse(
            LocalModelPolicy.canGenerate(
                LocalModelStatus(LocalModelState.READY, runsOnDevice = true)
            )
        )
    }

    @Test fun rejectsEveryNonReadyState() {
        listOf(
            LocalModelState.NOT_CONFIGURED,
            LocalModelState.DOWNLOADING,
            LocalModelState.INITIALIZING,
            LocalModelState.UNAVAILABLE,
            LocalModelState.FAILED
        ).forEach { state ->
            assertFalse(LocalModelPolicy.canGenerate(LocalModelStatus(state)))
        }
    }

    @Test fun preservesDiagnosticForUnavailableRuntime() {
        assertEquals(
            "Недостаточно памяти",
            LocalModelPolicy.unavailableReason(
                LocalModelStatus(LocalModelState.UNAVAILABLE, detail = "Недостаточно памяти")
            )
        )
    }
}
