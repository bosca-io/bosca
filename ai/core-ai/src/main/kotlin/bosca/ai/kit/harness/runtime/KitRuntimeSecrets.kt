package bosca.ai.kit.harness.runtime

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Generation-owned encrypted environment shared with the infrastructure controller. */
@Serializable
data class KitRuntimeSecrets(
    @Contextual val keyId: UUID,
    val nonce: String,
    val ciphertext: String,
    val names: List<String>,
)
