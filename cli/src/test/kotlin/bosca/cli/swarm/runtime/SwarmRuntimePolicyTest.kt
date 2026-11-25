package bosca.cli.swarm.runtime

import kotlinx.serialization.json.*
import kotlin.test.*

class SwarmRuntimePolicyTest {
    private val image = "runtime@sha256:" + "a".repeat(64)

    @Test fun `native profile enforces isolation and bounded resources before materialization`() {
        val profile = runtimeProfile(image)
        validateSwarmRuntime(profile, image)
        val task = profile.service.objectAt("TaskTemplate")
        val container = task.objectAt("ContainerSpec")
        for ((key, value) in listOf("User" to JsonPrimitive("root"), "ReadOnly" to JsonPrimitive(false), "Init" to JsonPrimitive(true),
            "CapabilityAdd" to JsonArray(listOf(JsonPrimitive("SYS_ADMIN"))), "Mounts" to JsonArray(emptyList()), "Privileges" to JsonObject(emptyMap()))) {
            assertFailsWith<IllegalArgumentException>(key) { validateSwarmRuntime(profile.copy(service = profile.service.updated("TaskTemplate", task.updated("ContainerSpec", container.updated(key, value)))), image) }
        }
        for ((key, value) in listOf("Resources" to JsonObject(emptyMap()), "RestartPolicy" to buildJsonObject { put("Condition", "any") }, "Networks" to JsonArray(emptyList()))) {
            assertFailsWith<IllegalArgumentException>(key) { validateSwarmRuntime(profile.copy(service = profile.service.updated("TaskTemplate", task.updated(key, value))), image) }
        }
        assertFailsWith<IllegalArgumentException> { validateSwarmRuntime(profile, "different@sha256:" + "b".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { validateSwarmRuntime(profile.copy(secretEnvironment = mapOf("BOSCA_TOKEN" to "provider")), image) }
    }
}
