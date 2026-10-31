package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Atlas host and capabilities of its combined process. */
@Serializable
data class SwarmAtlas(
    /** Optional hostname under the site’s shared cookie domain. */
    val domain: String = "",
    val servers: SwarmAtlasServers = SwarmAtlasServers(),
)
