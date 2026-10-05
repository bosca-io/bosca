package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Input for creating a new repository. The [slug] becomes part of the clone URL
 * and cannot be changed after creation.
 */
@Serializable
data class CreateRepositoryInput(
    val slug: String,
    val name: String,
    val description: String? = null,
    @Contextual val ownerId: UUID,
    val visibility: Visibility = Visibility.PRIVATE,
    val defaultBranch: String = "main",
    val contentType: RepositoryContentType? = null,
    val configuration: RepositoryConfiguration = RepositoryConfiguration(),
    val initializeWithReadme: Boolean = false,
    val gitignoreTemplate: String? = null,
    val licenseTemplate: String? = null
)

/**
 * Input for updating mutable repository fields. The [slug] is deliberately
 * absent — it is immutable after creation.
 */
@Serializable
data class UpdateRepositoryInput(
    val name: String? = null,
    val description: String? = null,
    val visibility: Visibility? = null,
    val defaultBranch: String? = null,
    val contentType: RepositoryContentType? = null,
    val configuration: RepositoryConfiguration? = null
)

/**
 * Input for forking a repository into a new owner's namespace.
 */
@Serializable
data class ForkRepositoryInput(
    @Contextual val sourceRepositoryId: UUID,
    @Contextual val newOwnerId: UUID,
    val slug: String? = null,
    val name: String? = null
)
