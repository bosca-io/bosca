package bosca.cli.swarm.runtime

import kotlinx.serialization.json.*

/** Validates the supported native ServiceSpec controls before any credential is mounted. */
internal fun validateSwarmRuntime(profile: SwarmRuntimeProfile, image: String) {
    val spec = profile.service
    require(spec.keys.all { it in setOf("TaskTemplate", "Mode", "EndpointSpec", "Labels") }) { "Unsupported Swarm service field" }
    val task = spec.objectAt("TaskTemplate")
    require(task.keys.all { it in setOf("ContainerSpec", "Resources", "RestartPolicy", "Placement", "Networks", "LogDriver") }) { "Unsupported Swarm task field" }
    val container = task.objectAt("ContainerSpec")
    require(container.keys.all { it in setOf("Image", "User", "ReadOnly", "Env", "Dir", "Mounts", "Privileges", "CapabilityDrop", "StopGracePeriod", "Labels", "Init", "Command", "Args") }) { "Unsupported Swarm container field" }
    require(image.matches(Regex("[^\\s@]+@sha256:[a-f0-9]{64}")) && container.textAt("Image") == image) { "Swarm profile image does not match the admitted image" }
    require(container.textAt("User") in setOf("node", "1000:1000") && container.boolAt("ReadOnly") == true && container.boolAt("Init") == false) { "Swarm runtime requires a non-root user, read-only root and image-owned init" }
    require(container.arrayAt("CapabilityDrop").map { it.jsonPrimitive.content }.toSet() == setOf("ALL")) { "Swarm runtime must drop all capabilities" }
    val privileges = container.objectAt("Privileges")
    require(privileges.keys == setOf("NoNewPrivileges") && privileges.boolAt("NoNewPrivileges") == true) { "Swarm runtime requires no-new-privileges" }
    require(container.textAt("Dir") == "/state" && (container.longAt("StopGracePeriod") ?: 0) in 1_000_000_000L..600_000_000_000L) { "Invalid Swarm working directory or termination grace" }
    require(container.arrayAt("Args").map { it.jsonPrimitive.content } == listOf("--root", "/state/session", "--private-pid-namespace")) { "Swarm runtime requires a private session directory and PID namespace" }
    val environment = container.arrayAt("Env").map { it.jsonPrimitive.content }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    require(environment["HOME"] == "/state/session/home" && environment["TMPDIR"] == "/state/session/scratch" &&
        listOf("CODEX_HOME", "CLAUDE_CONFIG_DIR").all { environment[it] == "/state/session/harness" } &&
        environment["CLAUDE_CODE_TMPDIR"] == "/state/session/scratch") { "Swarm runtime state must remain in its private session directory" }
    require(spec.objectAt("Mode").keys == setOf("Replicated") && spec.objectAt("Mode").objectAt("Replicated").longAt("Replicas") == 1L) { "Swarm runtime requires one replica" }
    require(spec.objectAt("EndpointSpec").arrayAt("Ports").isEmpty()) { "Swarm runtime ports must remain private" }
    require(task.objectAt("RestartPolicy").textAt("Condition") == "none") { "Swarm runtime restart policy must be none" }
    val limits = task.objectAt("Resources").objectAt("Limits")
    require((limits.longAt("NanoCPUs") ?: 0) > 0 && (limits.longAt("MemoryBytes") ?: 0) > 0 && (limits.longAt("Pids") ?: 0) in 1L..32768L) { "Swarm runtime requires CPU, memory and PID limits" }
    val mounts = container.arrayAt("Mounts").map { it.jsonObject }
    val mount = mounts.singleOrNull()
    val options = mount?.objectAt("TmpfsOptions") ?: JsonObject(emptyMap())
    require(mount != null && mount.keys.all { it in setOf("Type", "Target", "TmpfsOptions", "ReadOnly") } && mount.textAt("Target") == "/state" &&
        mount.textAt("Type") == "tmpfs" && mount.boolAt("ReadOnly") != true && options.keys == setOf("SizeBytes", "Mode", "Options") &&
        (options.longAt("SizeBytes") ?: 0) in 1L..(64L * 1024 * 1024 * 1024) && options.longAt("Mode") == 1023L &&
        options.arrayAt("Options") == JsonArray(listOf(JsonArray(listOf(JsonPrimitive("exec")))))) { "Swarm runtime requires one bounded tmpfs for private session state" }
    require(requireNotNull(options.longAt("SizeBytes")) < requireNotNull(limits.longAt("MemoryBytes"))) { "Swarm memory limit must leave room beyond its writable mount" }
    require(task.arrayAt("Networks").isNotEmpty() && task.arrayAt("Networks").all { it.jsonObject.textAt("Target")?.isNotBlank() == true }) { "Swarm runtime needs an approved overlay network" }
    require(profile.secretEnvironment.all { (key, value) -> key.matches(Regex("[A-Z][A-Z0-9_]*")) && !key.startsWith("BOSCA_") && value.matches(Regex("[a-zA-Z0-9_.-]+")) }) { "Invalid Swarm provider secret reference" }
    require(profile.registryAuthSecret?.let { it.matches(Regex("[a-zA-Z0-9_.-]+")) && it !in setOf(".", "..") } != false) { "Invalid Swarm registry credential reference" }
}
