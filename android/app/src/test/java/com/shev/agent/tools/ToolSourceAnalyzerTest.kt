package com.shev.agent.tools

import org.junit.Assert.*
import org.junit.Test

class ToolSourceAnalyzerTest {
    @Test fun flagsProcessAndNetworkApis() {
        val findings = ToolSourceAnalyzer.analyze(
            "Runtime.getRuntime().exec(\"id\")\nval url = java.net.URL(\"https://example.com\")"
        )
        assertTrue(findings.any { it.ruleId == "PROCESS_RUNTIME" })
        assertTrue(findings.any { it.ruleId == "NETWORK_ACCESS" })
        assertTrue(findings.all { it.severity == ToolFindingSeverity.BLOCKER })
    }

    @Test fun acceptsSimplePureKotlinSource() {
        assertTrue(ToolSourceAnalyzer.analyze("class SafeTool { fun sum(a:Int,b:Int)=a+b }").isEmpty())
    }

    @Test fun flagsDynamicLoadingNativeLibrariesAndWebViewBridges() {
        val findings = ToolSourceAnalyzer.analyze(
            """
            val loader = DexClassLoader(path, optimizedDir, null, parent)
            System.loadLibrary("native_helper")
            webView.addJavascriptInterface(bridge, "bridge")
            """.trimIndent()
        )
        assertTrue(findings.any { it.ruleId == "DYNAMIC_CLASS_LOADING" })
        assertTrue(findings.any { it.ruleId == "NATIVE_CODE_LOADING" })
        assertTrue(findings.any { it.ruleId == "WEBVIEW_BRIDGE" })
        assertTrue(findings.all { it.severity == ToolFindingSeverity.BLOCKER })
    }

    @Test fun dependencyPolicyUsesExplicitGroupAllowlist() {
        assertTrue(ToolDependencyPolicy.isAllowed("androidx.core:core-ktx:1.15.0"))
        assertTrue(ToolDependencyPolicy.isAllowed("org.jetbrains.kotlin:kotlin-stdlib:2.0.21"))
        assertFalse(ToolDependencyPolicy.isAllowed("com.unknown:opaque-sdk:1.0"))
    }
}
