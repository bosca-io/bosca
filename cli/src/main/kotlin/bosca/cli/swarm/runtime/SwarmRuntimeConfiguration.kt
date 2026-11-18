package bosca.cli.swarm.runtime

import kotlinx.serialization.Serializable

/** Manager-local configuration; workers and Runner never receive the Docker socket or these keys. */
@Serializable
data class SwarmRuntimeConfiguration(val profiles: Map<String, SwarmRuntimeProfile>,
    val socket: String = "/var/run/docker.sock", val endpointTemplate: String = "http://{service}:8080",
    val tokenEnvironment: String = "BOSCA_SWARM_CONTROLLER_TOKEN",
    val encryptionKeyEnvironment: String = "SECURITY_ENCRYPTION_KEY")
