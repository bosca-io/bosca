package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.workops.model.artifact.ArtifactPublication

// ── Marker objects ────────────────────────────────────────────────────

@TypeController(type = "WorkOpsArtifactPublication")
class ArtifactPublicationTypeController : GraphQLController<ArtifactPublication> {
    @Field fun id(a: ArtifactPublication) = a.id
    @Field fun versionId(a: ArtifactPublication) = a.versionId
    @Field fun projectId(a: ArtifactPublication) = a.projectId
    @Field fun artifactType(a: ArtifactPublication) = a.artifactType
    @Field fun coordinates(a: ArtifactPublication) = a.coordinates
    @Field fun repositoryUrl(a: ArtifactPublication) = a.repositoryUrl
    @Field fun publishedAt(a: ArtifactPublication) = a.publishedAt
    @Field fun publishedByPrincipalId(a: ArtifactPublication) = a.publishedByPrincipalId
    @Field fun checksumSha256(a: ArtifactPublication) = a.checksumSha256
    @Field fun status(a: ArtifactPublication) = a.status
    @Field fun externalUrl(a: ArtifactPublication) = a.externalUrl
    @Field fun namespace(a: ArtifactPublication) = a.namespace
    @Field fun environments(a: ArtifactPublication) = a.environments
    @Field fun version(a: ArtifactPublication) = a.version
}
