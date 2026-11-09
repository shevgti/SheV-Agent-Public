package com.shev.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityPolicyTest {
    @Test fun lowRiskAllowedOnlyWhenCapabilityWasGranted() {
        assertEquals(
            ApprovalDecision.ALLOW,
            CapabilityPolicy.decide(AgentCapability.ANALYZE_DATA, false, true)
        )
        assertEquals(
            ApprovalDecision.DENY,
            CapabilityPolicy.decide(AgentCapability.ANALYZE_DATA, true, false)
        )
    }

    @Test fun remoteReadRequiresActionSpecificApproval() {
        assertEquals(
            ApprovalDecision.REQUIRE_APPROVAL,
            CapabilityPolicy.decide(AgentCapability.READ_REMOTE_DATA, false, true)
        )
        assertEquals(
            ApprovalDecision.ALLOW,
            CapabilityPolicy.decide(AgentCapability.READ_REMOTE_DATA, true, true)
        )
    }

    @Test fun writingFilesRequiresApproval() {
        assertEquals(
            ApprovalDecision.REQUIRE_APPROVAL,
            CapabilityPolicy.decide(AgentCapability.WRITE_PROJECT_FILES, false, true)
        )
    }

    @Test fun criticalActionsAlwaysRequireExplicitApproval() {
        val critical = listOf(
            AgentCapability.SEND_MESSAGE_OR_PUBLISH,
            AgentCapability.SPEND_MONEY,
            AgentCapability.DELETE_OR_OVERWRITE_DATA,
            AgentCapability.CHANGE_ACCESS_OR_SECURITY,
            AgentCapability.CONTROL_DEVICE_SETTINGS
        )
        critical.forEach { capability ->
            assertEquals(
                capability.name,
                ApprovalDecision.REQUIRE_APPROVAL,
                CapabilityPolicy.decide(capability, false, true)
            )
        }
    }

    @Test fun everyCapabilityHasRussianUserFacingMetadata() {
        AgentCapability.values().forEach { capability ->
            assertEquals(capability.name, true, capability.title.isNotBlank())
            assertEquals(capability.name, true, capability.description.isNotBlank())
            assertEquals(capability.name, true, capability.title.none { it in 'A'..'Z' })
        }
    }

    @Test fun explicitApprovalIsStillBoundToGrantedCapability() {
        assertEquals(
            ApprovalDecision.DENY,
            CapabilityPolicy.decide(AgentCapability.SPEND_MONEY, true, false)
        )
    }
}
