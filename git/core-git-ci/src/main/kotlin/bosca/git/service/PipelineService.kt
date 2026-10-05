package bosca.git.service

import bosca.git.model.ArtifactDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineDefinition
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages pipeline definitions parsed from `.bosca/pipelines/\*.yaml` files.
 * Syncs pipeline records with the YAML files in the repository on each push,
 * creating, updating, or archiving pipelines as files change.
 */
interface PipelineService : Service {

    /**
     * Reconciles the live catalog with the current default branch, archiving removed files while
     * preserving pipeline IDs, runs, and trigger occurrences. [ref] determines whether default-branch
     * schedules and environments are announced. Returns pipeline metadata from [commitSha] with
     * stable persisted IDs for trigger evaluation; historical definitions never overwrite the catalog.
     */
    suspend fun syncPipelines(repositoryId: UUID, ref: String, commitSha: String): List<Pipeline>

    /**
     * Retrieves all pipeline definitions for a repository.
     */
    suspend fun findByRepository(repositoryId: UUID): List<Pipeline>

    /** Every pipeline definition across all repositories — the author-time picker for release relays. */
    suspend fun all(): List<Pipeline>

    /**
     * The artifacts [pipelineId]'s definition **declares** it produces (parsed from its YAML at the
     * repository's default branch), deduplicated. Author-time source for the "Use Artifact" picker; empty
     * if the pipeline, repository, or definition can't be read. Coordinates are the raw `${{ }}` templates.
     */
    suspend fun declaredArtifacts(pipelineId: UUID): List<ArtifactDefinition>

    /**
     * Retrieves a pipeline by its unique identifier.
     */
    suspend fun findById(id: UUID): Pipeline?

    /** A repository's pipeline by its declared name — how pipeline requirements resolve. */
    suspend fun findByRepositoryAndName(repositoryId: UUID, name: String): Pipeline?

    /**
     * Parses a pipeline YAML file and returns the in-memory definition
     * without persisting. Used for validation and preview.
     */
    suspend fun parseDefinition(repositoryId: UUID, ref: String, filePath: String): PipelineDefinition?

    /**
     * Deletes a pipeline definition and all associated runs.
     */
    suspend fun delete(id: UUID)
}
