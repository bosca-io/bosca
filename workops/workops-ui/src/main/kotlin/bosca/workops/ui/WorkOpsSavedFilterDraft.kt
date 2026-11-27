package bosca.workops.ui

import kotlinx.serialization.Serializable

@Serializable
data class WorkOpsSavedFilterDraft(val id: String = "", val name: String = "", val description: String = "", val source: String = "", val version: Long = 0)
