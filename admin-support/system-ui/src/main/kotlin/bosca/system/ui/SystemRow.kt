package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemRow(
    val id: String, val title: String, val detail: String, val status: String = "", val enabled: Boolean = false,
    val cron: String? = null, val lastRun: String? = null, val nextRun: String? = null,
)
