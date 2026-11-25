package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Normalized `host:port` destinations the egress proxy may open for one runtime generation. */
@Serializable
data class KitEgressDestinations(val destinations: List<String>)
