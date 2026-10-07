package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactSync
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("ArtifactSync")
class ArtifactSyncController : GraphQLController<ArtifactSync> {
    @Field
    fun id(value: ArtifactSync) = value.id

    @Field
    fun destinationId(value: ArtifactSync) = value.destinationId

    @Field
    fun versionId(value: ArtifactSync) = value.versionId

    @Field
    fun tagName(value: ArtifactSync) = value.tagName

    @Field
    fun manifestDigest(value: ArtifactSync) = value.manifestDigest

    @Field
    fun attempts(value: ArtifactSync) = value.attempts

    @Field
    fun synced(value: ArtifactSync) = value.synced

    @Field
    fun error(value: ArtifactSync) = value.error

    @Field
    fun created(value: ArtifactSync) = value.created

    @Field
    fun modified(value: ArtifactSync) = value.modified

}
