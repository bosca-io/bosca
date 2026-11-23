package bosca.cli.swarm.runtime

import bosca.ai.kit.harness.KitSwarmRequest
import bosca.ai.kit.harness.runtime.KitRuntimeSecrets
import bosca.security.encryption.EncryptionServiceImpl
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.test.*

class SwarmRuntimeEgressTest {
    private val image = "runtime@sha256:" + "a".repeat(64)
    private val application = BoscaApplication(ApplicationConfig.load("security: {encryption: {key: isolated-test-key}}".byteInputStream()), ownsServices = false)
    private val encryption = EncryptionServiceImpl(application)
    private val resources = mutableMapOf<String, JsonObject>()
    private val engine = object : SwarmEngine {
        override suspend fun request(method: String, path: String, body: JsonElement?, headers: Map<String, String>): SwarmEngineResponse = when {
            method == "GET" && path == "/info" -> SwarmEngineResponse(200, buildJsonObject { putJsonObject("Swarm") { put("ControlAvailable", true) } })
            method == "GET" && path.startsWith("/networks/") -> SwarmEngineResponse(200, buildJsonObject { put("Driver", "overlay") })
            method == "GET" && path.startsWith("/tasks") -> SwarmEngineResponse(200, JsonArray(emptyList()))
            method == "POST" && path.endsWith("/create") -> {
                val spec = requireNotNull(body).jsonObject
                val name = requireNotNull(spec.textAt("Name"))
                resources[path.removePrefix("/").removeSuffix("/create") + "/" + name] = buildJsonObject {
                    put("ID", name); put("Spec", spec); put("CreatedAt", "2026-10-31T00:00:00Z")
                }
                SwarmEngineResponse(201, JsonObject(emptyMap()))
            }
            method == "GET" -> resources[path.removePrefix("/")]?.let { SwarmEngineResponse(200, it) } ?: SwarmEngineResponse(404, JsonNull)
            else -> error("Unexpected Docker Engine request $method $path")
        }
    }
    private fun service(profile: SwarmRuntimeProfile = runtimeProfile(image)) =
        SwarmRuntimeService(engine, encryption, SwarmRuntimeConfiguration(mapOf("fixture" to profile)), application.json)

    private suspend fun request(secrets: Map<String, String>, environment: Map<String, String> = emptyMap(), names: List<String> = secrets.keys.toList()): KitSwarmRequest {
        val session = UUID.random()
        val encrypted = encryption.encrypt(application.json.encodeToString(MapSerializer(String.serializer(), String.serializer()), secrets).toByteArray(), session)
        return KitSwarmRequest("fixture", "kit:$session:1", image, mapOf("BOSCA_CHAT_SESSION_ID" to session.toString(), "BOSCA_RUNTIME_GENERATION" to "1",
            "BOSCA_ENDPOINT" to "http://bosca:8080/graphql", "BOSCA_ARCHIVE_MAX_BYTES" to "10000000", "BOSCA_JOURNAL_MAX_BYTES" to "1000000",
            "BOSCA_JOURNAL_MAX_TURNS" to "256", "BOSCA_MCP_SECRET_NAMES" to "[]") + environment,
            KitRuntimeSecrets(session, Base64.getEncoder().encodeToString(encrypted.nonce), Base64.getEncoder().encodeToString(encrypted.data), names))
    }
    private val generation = mapOf("BOSCA_TOKEN" to "bsk_fixture", "BOSCA_RUNTIME_TOKEN" to "control-fixture",
        "BOSCA_RUNTIME_EXPIRES" to (System.currentTimeMillis() / 1000 + 600).toString())

    @Test fun `egress runtimes carry the proxy origin in the environment and the token in the runtime secret`(): Unit = runBlocking {
        val request = request(generation + ("BOSCA_EGRESS_TOKEN" to "egress-fixture"), mapOf("BOSCA_EGRESS_PROXY" to "http://kit-egress:3128"))
        service().ensure(UUID.random(), request)
        val created = resources.entries.single { it.key.startsWith("services/") }.value.objectAt("Spec")
        val environment = created.objectAt("TaskTemplate").objectAt("ContainerSpec").arrayAt("Env").map { it.jsonPrimitive.content }
        assertContains(environment, "BOSCA_EGRESS_PROXY=http://kit-egress:3128")
        assertFalse(created.toString().contains("egress-fixture"), "The egress token stays in the runtime secret")
        val secret = resources.entries.single { it.key.startsWith("secrets/") }.value.objectAt("Spec")
        val values = Json.parseToJsonElement(Base64.getDecoder().decode(requireNotNull(secret.textAt("Data"))).decodeToString()).jsonObject
        assertEquals("egress-fixture", values.textAt("BOSCA_EGRESS_TOKEN"))
    }

    @Test fun `runtimes without an egress proxy keep the existing generation fields`(): Unit = runBlocking {
        service().ensure(UUID.random(), request(generation))
        val created = resources.entries.single { it.key.startsWith("services/") }.value.objectAt("Spec")
        assertTrue(created.objectAt("TaskTemplate").objectAt("ContainerSpec").arrayAt("Env").none { it.jsonPrimitive.content.startsWith("BOSCA_EGRESS") })
    }

    @Test fun `unknown missing or malformed generation fields are rejected before Docker writes`(): Unit = runBlocking {
        val invalid = listOf(
            request(generation, mapOf("BOSCA_EGRESS_PROXY" to "http://user:secret@kit-egress:3128")),
            request(generation, mapOf("BOSCA_EGRESS_PROXY" to "https://kit-egress:3128")),
            request(generation, mapOf("BOSCA_UNKNOWN" to "value")),
            request(generation + ("BOSCA_UNKNOWN" to "value")),
            request(generation - "BOSCA_RUNTIME_TOKEN"),
            request(generation + ("BOSCA_EGRESS_TOKEN" to "egress-fixture"), names = generation.keys.toList()),
        )
        for (request in invalid) assertFailsWith<IllegalArgumentException> { service().ensure(UUID.random(), request) }
        assertTrue(resources.isEmpty())
    }

    @Test fun `profiles cannot set generation-owned egress settings`(): Unit = runBlocking {
        val profile = runtimeProfile(image)
        val task = profile.service.objectAt("TaskTemplate")
        val container = task.objectAt("ContainerSpec")
        for (entry in listOf("BOSCA_EGRESS_PROXY=http://elsewhere:3128", "BOSCA_EGRESS_TOKEN=profile")) {
            val overridden = profile.copy(service = profile.service.updated("TaskTemplate", task.updated("ContainerSpec",
                container.updated("Env", JsonArray(container.arrayAt("Env") + JsonPrimitive(entry))))))
            val failure = assertFailsWith<IllegalArgumentException> { service(overridden).ensure(UUID.random(), request(generation)) }
            assertEquals("Swarm profile overrides generation-owned environment", failure.message)
        }
        assertTrue(resources.keys.none { it.startsWith("services/") })
    }
}
