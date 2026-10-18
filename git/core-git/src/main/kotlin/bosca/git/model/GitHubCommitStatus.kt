package bosca.git.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub's commit status payload; the context identifies one required check. */
@Serializable
data class GitHubCommitStatus(
    val state: String,
    val context: String,
    val description: String? = null,
    @SerialName("target_url") val targetUrl: String? = null,
)
