package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactSyncDestination
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("ArtifactSyncDestination")
class ArtifactSyncDestinationController : GraphQLController<ArtifactSyncDestination> {
    @Field
    fun id(value: ArtifactSyncDestination) = value.id

    @Field
    fun repositoryId(value: ArtifactSyncDestination) = value.repositoryId

    @Field
    fun key(value: ArtifactSyncDestination) = value.key

    @Field
    fun remoteRepository(value: ArtifactSyncDestination) = value.remoteRepository

    @Field
    fun username(value: ArtifactSyncDestination) = value.username

    @Field
    fun tokenSecretName(value: ArtifactSyncDestination) = value.tokenSecretName

    @Field
    fun enabled(value: ArtifactSyncDestination) = value.enabled

    @Field
    fun version(value: ArtifactSyncDestination) = value.version

    @Field
    fun created(value: ArtifactSyncDestination) = value.created

    @Field
    fun modified(value: ArtifactSyncDestination) = value.modified

}
