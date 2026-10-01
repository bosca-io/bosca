package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.project.Project
import bosca.workops.model.version.Version
import bosca.workops.service.EnvironmentDrift
import bosca.workops.service.ProjectService
import bosca.workops.service.VersionService

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsEnvironmentDrift")
class EnvironmentDriftTypeController(
    private val projectService: ProjectService,
    private val versionService: VersionService,
) : GraphQLController<EnvironmentDrift> {
    @Field fun projectId(d: EnvironmentDrift) = d.projectId
    @Field fun deployedVersionId(d: EnvironmentDrift) = d.deployedVersionId
    @Field fun expectedVersionId(d: EnvironmentDrift) = d.expectedVersionId
    @Field fun driftType(d: EnvironmentDrift) = d.driftType

    /** Resolved project + the deployed/expected versions so drift reads in names, not raw UUIDs. */
    @Field suspend fun project(d: EnvironmentDrift): Project? = projectService.getById(d.projectId)
    @Field suspend fun deployedVersion(d: EnvironmentDrift): Version? = d.deployedVersionId?.let { versionService.getById(it) }
    @Field suspend fun expectedVersion(d: EnvironmentDrift): Version? = versionService.getById(d.expectedVersionId)
}
