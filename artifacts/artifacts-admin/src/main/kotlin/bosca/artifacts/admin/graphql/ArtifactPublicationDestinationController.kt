package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactPublicationDestination
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("ArtifactPublicationDestination")
class ArtifactPublicationDestinationController : GraphQLController<ArtifactPublicationDestination> {
    @Field
    fun id(value: ArtifactPublicationDestination) = value.id

    @Field
    fun repositoryId(value: ArtifactPublicationDestination) = value.repositoryId

    @Field
    fun key(value: ArtifactPublicationDestination) = value.key

    @Field
    fun githubRepositoryId(value: ArtifactPublicationDestination) = value.githubRepositoryId

    @Field
    fun owner(value: ArtifactPublicationDestination) = value.owner

    @Field
    fun githubRepository(value: ArtifactPublicationDestination) = value.githubRepository

    @Field
    fun tagPrefix(value: ArtifactPublicationDestination) = value.tagPrefix

    @Field
    fun tokenSecretName(value: ArtifactPublicationDestination) = value.tokenSecretName

    @Field
    fun enabled(value: ArtifactPublicationDestination) = value.enabled

    @Field
    fun version(value: ArtifactPublicationDestination) = value.version

    @Field
    fun created(value: ArtifactPublicationDestination) = value.created

    @Field
    fun modified(value: ArtifactPublicationDestination) = value.modified
}
