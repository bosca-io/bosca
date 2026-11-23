package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Origin of an effective model or reasoning setting; HARNESS leaves the choice to its native default. */
@Serializable
enum class KitModelSettingsSource {
    HARNESS,
    SYSTEM,
    AGENT,
    CHAT,
}
