package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A pointer into a specific location within a Bosca-hosted git repository,
 * used to pin content to a particular ref and path.
 *
 * The [ref] can be a branch name (`main`), a tag (`v2.1.0`), or a full commit SHA.
 * The [path] is optional and narrows the scope to a subdirectory or file within the tree.
 */
@Serializable
data class RepositoryRef(
    @Contextual val repositoryId: UUID,
    val ref: String = "main",
    val path: String? = null
)
