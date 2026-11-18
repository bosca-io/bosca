package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Controls a standalone service independently of Atlas's enabled capabilities. */
@Serializable
data class SwarmStandaloneServer(val enabled: Boolean = false)
