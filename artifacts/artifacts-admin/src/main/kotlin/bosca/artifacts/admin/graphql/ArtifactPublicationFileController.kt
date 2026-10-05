package bosca.artifacts.admin.graphql

import bosca.artifacts.model.ArtifactPublicationFile
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("ArtifactPublicationFile")
class ArtifactPublicationFileController : GraphQLController<ArtifactPublicationFile> {
    @Field
    fun filename(value: ArtifactPublicationFile) = value.filename

    @Field
    fun digest(value: ArtifactPublicationFile) = value.digest

    @Field
    fun size(value: ArtifactPublicationFile) = value.size

    @Field
    fun mediaType(value: ArtifactPublicationFile) = value.mediaType
}
