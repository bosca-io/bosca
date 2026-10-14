package bosca.git.ui

import kotlinx.serialization.Serializable

@Serializable
data class CommitSummary(val sha: String, val message: String, val author: String, val date: String)
