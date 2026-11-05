package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Installed runtime components and the protocols implemented by the supervisor. */
@Serializable
data class RuntimeVersions(
    val cli: String,
    val adapter: String,
    val harness: String,
    val statePolicy: String,
    val transport: Int = 1,
    val acp: Int = 1,
    val archive: Int = 1,
)
