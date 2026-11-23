package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Origin of the effective harness profile selection. */
@Serializable
enum class KitHarnessSource {
    SYSTEM,
    AGENT,
    CHAT,
    DELEGATION,
}
