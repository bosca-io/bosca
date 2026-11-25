package bosca.kubernetes.jobs

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Encrypted per-dispatch environment, materialized as an owner-bound Kubernetes Secret. */
@Serializable
data class KubernetesJobSecrets(
    @Contextual val keyId: UUID,
    val nonce: String,
    val ciphertext: String,
    val names: List<String>,
)
