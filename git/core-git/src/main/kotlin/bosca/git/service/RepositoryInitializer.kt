package bosca.git.service

import bosca.git.model.CreateRepositoryInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Creates an initial commit in a newly-created DFS repository with optional
 * README, .gitignore, and LICENSE files sourced from bundled templates.
 */
interface RepositoryInitializer : Service {

    /**
     * Populates [repositoryId] with an initial commit containing the files
     * requested by [input]. Returns the byte size of the objects written,
     * or zero if no initialization was requested.
     */
    suspend fun initialize(repositoryId: UUID, input: CreateRepositoryInput): Long
}
