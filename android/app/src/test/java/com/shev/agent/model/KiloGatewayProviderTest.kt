package com.shev.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KiloGatewayProviderTest {
    @Test
    fun catalogueIncludesOnlyZeroCostModelsWithToolSupport() {
        val json = """
            {
              "data": [
                {
                  "id": "vendor/free-tools",
                  "pricing": {"prompt": "0", "completion": "0.000"},
                  "supported_parameters": ["tools", "temperature"],
                  "context_length": 32768
                },
                {
                  "id": "vendor/paid-tools",
                  "pricing": {"prompt": "0.000001", "completion": "0"},
                  "supported_parameters": ["tools"]
                },
                {
                  "id": "vendor/free-no-tools",
                  "pricing": {"prompt": "0", "completion": "0"},
                  "supported_parameters": ["temperature"]
                },
                {
                  "id": "vendor/missing-pricing",
                  "supported_parameters": ["tools"]
                },
                {
                  "id": "vendor/free-tools",
                  "pricing": {"prompt": "0", "completion": "0"},
                  "supported_parameters": ["tools"]
                }
              ]
            }
        """.trimIndent()

        val models = KiloGatewayProvider.parseFreeToolModels(json)

        assertEquals(1, models.size)
        assertEquals("vendor/free-tools", models.single().id)
        assertEquals(32768, models.single().contextLength)
    }

    @Test
    fun malformedCatalogueFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            KiloGatewayProvider.parseFreeToolModels("""{"models":[]}""")
        }
    }

    @Test
    fun malformedIndividualEntriesAreSkippedWithoutLosingValidModels() {
        val json = """
            {
              "data": [
                {"id":"vendor/bad-pricing-object","pricing":"free","supported_parameters":["tools"]},
                {"id":"vendor/bad-parameters","pricing":{"prompt":"0","completion":"0"},"supported_parameters":"tools"},
                {"id":"vendor/bad-id","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]},
                {"id":"vendor/good","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":"unknown"}
              ]
            }
        """.trimIndent()

        val models = KiloGatewayProvider.parseFreeToolModels(json)

        assertEquals(listOf(KiloFreeToolModel("vendor/good")), models)
    }

    @Test
    fun openAiCompatibleModelCanBeCreatedWithoutAnApiKey() {
        val model = KiloGatewayProvider.createModel(KiloFreeToolModel("vendor/free-tools"))
        assertNotNull(model)
    }

    @Test
    fun nonObjectEntriesAreIgnored() {
        val models = KiloGatewayProvider.parseFreeToolModels("""{"data":[null, 5, "bad"]}""")
        assertEquals(emptyList<KiloFreeToolModel>(), models)
    }
}
