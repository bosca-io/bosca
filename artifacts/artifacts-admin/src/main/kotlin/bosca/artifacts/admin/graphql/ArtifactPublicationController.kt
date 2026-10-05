package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactPublication
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("ArtifactPublication")
class ArtifactPublicationController : GraphQLController<ArtifactPublication> {
    @Field
    fun id(value: ArtifactPublication) = value.id

    @Field
    fun destinationId(value: ArtifactPublication) = value.destinationId

    @Field
    fun versionId(value: ArtifactPublication) = value.versionId

    @Field
    fun tagName(value: ArtifactPublication) = value.tagName

    @Field
    fun commitSha(value: ArtifactPublication) = value.commitSha

    @Field
    fun prerelease(value: ArtifactPublication) = value.prerelease

    @Field
    fun files(value: ArtifactPublication) = value.files

    @Field
    fun releaseId(value: ArtifactPublication) = value.releaseId

    @Field
    fun attempts(value: ArtifactPublication) = value.attempts

    @Field
    fun published(value: ArtifactPublication) = value.published

    @Field
    fun verified(value: ArtifactPublication) = value.verified

    @Field
    fun error(value: ArtifactPublication) = value.error

    @Field
    fun created(value: ArtifactPublication) = value.created

    @Field
    fun modified(value: ArtifactPublication) = value.modified
}
