package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** State to retain when replacing a native session after its context changes. */
@Serializable
enum class KitContextReset {
    WORKSPACE,
    FRESH,
}
