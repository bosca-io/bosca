package bosca.backup

import kotlinx.serialization.Serializable

/** Stable discovery intent, without credentials or a claimed database recovery boundary. */
@Serializable
data class ObjectBackupDiscoveryRequest(
    val id: String,
    val sourceId: String,
    val scope: String,
    val destinationId: String,
    val stores: Map<String, ObjectBackupSource>,
    val payloadPrefix: String,
    val manifestPath: String,
)
