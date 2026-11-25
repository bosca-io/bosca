package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Infrastructure selected by a managed harness profile, independently of its native harness. */
@Serializable
enum class KitInfrastructure { KUBERNETES, SWARM }
