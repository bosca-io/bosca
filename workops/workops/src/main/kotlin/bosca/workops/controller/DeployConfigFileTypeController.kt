package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.deploy.CreatedDeployConfig

// ── Marker objects ────────────────────────────────────────────────────

/** Resolves `WorkOpsDeployConfigFile` — the starter deploy.yaml committed by createProjectDeployConfig. */
@TypeController(type = "WorkOpsDeployConfigFile")
class DeployConfigFileTypeController : GraphQLController<CreatedDeployConfig> {
    @Field fun repositoryId(f: CreatedDeployConfig) = f.repositoryId
    @Field fun branch(f: CreatedDeployConfig) = f.branch
    @Field fun path(f: CreatedDeployConfig) = f.path
    @Field fun commitSha(f: CreatedDeployConfig) = f.commitSha
    @Field fun content(f: CreatedDeployConfig) = f.content
}
