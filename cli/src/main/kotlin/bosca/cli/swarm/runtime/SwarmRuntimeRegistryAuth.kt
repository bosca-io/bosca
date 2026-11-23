package bosca.cli.swarm.runtime

import kotlinx.serialization.Serializable

/** Docker's registry authentication payload, mounted only in the manager controller. */
@Serializable
internal data class SwarmRuntimeRegistryAuth(val username: String, val password: String, val serveraddress: String)
