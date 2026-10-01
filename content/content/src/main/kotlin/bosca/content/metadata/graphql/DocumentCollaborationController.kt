package bosca.content.metadata.graphql

import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.service.MetadataService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController
class DocumentCollaborationController(
    val service: MetadataService
) : GraphQLController<DocumentCollaboration> {

    @Field
    fun content(collaboration: DocumentCollaboration) = collaboration.content

    @Field
    fun created(collaboration: DocumentCollaboration) = collaboration.created

    @Field
    fun modified(collaboration: DocumentCollaboration) = collaboration.modified
}