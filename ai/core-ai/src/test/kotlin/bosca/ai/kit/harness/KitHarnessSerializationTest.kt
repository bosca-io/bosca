package bosca.ai.kit.harness

import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class KitHarnessSerializationTest {
    private val json = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()), ownsServices = false).json

    @Test
    fun `persisted resolution preserves explicit absence of native authentication`() {
        val configuration = configuration(null)
        val resolution = KitHarnessResolution(UUID.random(), "policy", "SYSTEM", configuration,
            KitRuntimeAuthority(listOf("content:view"), emptyList(), null))

        assertEquals(resolution, json.decodeFromJsonElement(KitHarnessResolution.serializer(),
            json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution)))
    }

    @Test
    fun `persisted configuration preserves an explicit native authentication method`() {
        val configuration = configuration("api-key")

        assertEquals(configuration, json.decodeFromJsonElement(KitHarnessConfiguration.serializer(),
            json.encodeToJsonElement(KitHarnessConfiguration.serializer(), configuration)))
    }

    @Test
    fun `persisted authority preserves browser and scoped caller ceilings`() {
        for (callerScopes in listOf(null, emptyList(), listOf("content:view"), listOf("content:view", "search:read"))) {
            val authority = KitRuntimeAuthority(listOf("content:view"), emptyList(), callerScopes)

            assertEquals(authority, json.decodeFromJsonElement(KitRuntimeAuthority.serializer(),
                json.encodeToJsonElement(KitRuntimeAuthority.serializer(), authority)))
        }
    }

    private fun configuration(authenticationMethod: String?) = KitHarnessConfiguration(
        "kit", "http://{job}.{namespace}.svc:8080", "https://bosca.invalid/graphql",
        authenticationMethod = authenticationMethod, scopes = listOf("content:view"),
        image = "runtime@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64),
        versions = RuntimeVersions("1", "2", "3", "policy"),
    )
}
