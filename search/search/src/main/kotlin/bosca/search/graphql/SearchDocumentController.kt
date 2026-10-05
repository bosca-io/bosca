package bosca.search.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.search.model.SearchDocument

@TypeController
class SearchDocumentController : GraphQLController<SearchDocument> {

    @Field
    fun metadata(document: SearchDocument) = document.metadata

    @Field
    fun collection(document: SearchDocument) = document.collection

    @Field
    fun profile(document: SearchDocument) = document.profile
}
