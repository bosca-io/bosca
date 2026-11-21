package bosca.kubernetes.controller.models

import kotlinx.serialization.Serializable

/** Current activation intent; pending exports never displace the active generation during loading. */
@Serializable
data class ServingModelContext(
    val id: String,
    val selectionRevision: Long,
    val activeModelVersion: Long? = null,
    val requestedModelVersion: Long? = null,
)
