package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.KitMcpConnection
import bosca.ai.kit.harness.KitMcpOption

/** Approved connections and safe choices resolved from current Agent assignments. */
data class KitMcpCatalog(
    val connections: List<KitMcpConnection>,
    val options: List<KitMcpOption>,
    val notices: List<String>,
)
