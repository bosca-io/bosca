package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactTag
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves fields on the `ArtifactTagInfo` GraphQL type.
 * All fields are scalar properties read directly from the model.
 */
@TypeController("ArtifactTagInfo")
class ArtifactTagInfoController : GraphQLController<ArtifactTag> {

    @Field
    fun id(tag: ArtifactTag) = tag.id

    @Field
    fun repositoryId(tag: ArtifactTag) = tag.repositoryId

    @Field
    fun name(tag: ArtifactTag) = tag.name

    @Field
    fun manifestDigest(tag: ArtifactTag) = tag.manifestDigest

    @Field
    fun created(tag: ArtifactTag) = tag.created

    @Field
    fun modified(tag: ArtifactTag) = tag.modified
}
