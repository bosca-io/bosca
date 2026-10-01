package bosca.workops.model.project

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A git repository a project owns. Associating repos with the project keeps the
 * release relay generic: a node reads the project's repos from data to tag/build them, rather than baking
 * a repositoryId into the pipeline. A project can own several. Managed on the project. [repositoryId]
 * references a git-module `Repository` (no cross-module FK).
 */
@Serializable
data class ProjectRepository(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("repository_id")
    @Contextual
    val repositoryId: UUID,
)
