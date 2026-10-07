package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** The host whose reviewed branch/tag value is retained when resolving one conflict. */
@Serializable
enum class GitHubRefResolution { BOSCA, GITHUB }

/** Both observed values are required for the resolution lease; null represents a deleted ref. */
@Serializable
data class GitHubRefResolutionInput(
    @Contextual val repositoryId: UUID,
    val ref: String,
    val resolution: GitHubRefResolution,
    val expectedBoscaSha: String? = null,
    val expectedGitHubSha: String? = null,
)
