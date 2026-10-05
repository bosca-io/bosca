package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.project.Project
import bosca.workops.model.version.Version
import bosca.workops.service.ProjectService
import bosca.workops.service.VersionService

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsEnvironmentDeployment")
class EnvironmentDeploymentTypeController(
    private val projectService: ProjectService,
    private val versionService: VersionService,
) : GraphQLController<EnvironmentDeployment> {
    @Field fun id(d: EnvironmentDeployment) = d.id
    @Field fun environmentId(d: EnvironmentDeployment) = d.environmentId
    @Field fun projectId(d: EnvironmentDeployment) = d.projectId
    @Field fun targetKind(d: EnvironmentDeployment) = d.targetKind
    @Field fun versionId(d: EnvironmentDeployment) = d.versionId
    @Field fun releaseId(d: EnvironmentDeployment) = d.releaseId
    @Field fun artifactPublicationId(d: EnvironmentDeployment) = d.artifactPublicationId
    @Field fun appBuildNumberAllocationId(d: EnvironmentDeployment) = d.appBuildNumberAllocationId
    @Field fun status(d: EnvironmentDeployment) = d.status
    @Field fun deployedAt(d: EnvironmentDeployment) = d.deployedAt
    @Field fun deployedByPrincipalId(d: EnvironmentDeployment) = d.deployedByPrincipalId
    @Field fun healthCheckStatus(d: EnvironmentDeployment) = d.healthCheckStatus
    @Field fun healthCheckUrl(d: EnvironmentDeployment) = d.healthCheckUrl
    @Field fun lastHealthCheckAt(d: EnvironmentDeployment) = d.lastHealthCheckAt
    @Field fun previousDeploymentId(d: EnvironmentDeployment) = d.previousDeploymentId
    @Field fun version(d: EnvironmentDeployment) = d.version

    /** Resolved project + version so callers render names, not raw UUIDs. */
    @Field suspend fun project(d: EnvironmentDeployment): Project? = projectService.getById(d.projectId)
    @Field suspend fun deployedVersion(d: EnvironmentDeployment): Version? = versionService.getById(d.versionId)
}
