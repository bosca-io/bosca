package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemReindexDraft(
    val deleteFirst: Boolean = false, val metadata: Boolean = true,
    val collections: Boolean = true, val profiles: Boolean = true, val confirmed: Boolean = false,
)
