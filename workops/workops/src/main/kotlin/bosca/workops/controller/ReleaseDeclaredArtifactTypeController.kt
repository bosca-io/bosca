package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.ReleaseDeclaredArtifact

/** Field wiring for `WorkOpsReleaseDeclaredArtifact` — an artifact a release WILL publish, per its CI declarations. */
@TypeController(type = "WorkOpsReleaseDeclaredArtifact")
class ReleaseDeclaredArtifactTypeController : GraphQLController<ReleaseDeclaredArtifact> {
    @Field fun projectId(a: ReleaseDeclaredArtifact): UUID = a.projectId
    @Field fun type(a: ReleaseDeclaredArtifact): ArtifactType = a.type
    @Field fun namespace(a: ReleaseDeclaredArtifact): String = a.namespace
    @Field fun coordinate(a: ReleaseDeclaredArtifact): String = a.coordinate
    @Field fun environments(a: ReleaseDeclaredArtifact): List<String> = a.environments
}
