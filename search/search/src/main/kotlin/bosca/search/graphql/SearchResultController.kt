package bosca.search.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.search.model.SearchResult

@TypeController
class SearchResultController : GraphQLController<SearchResult> {

    @Field
    fun documents(searchResult: SearchResult) = searchResult.documents

    @Field
    fun facets(searchResult: SearchResult) = searchResult.facets

    @Field
    fun estimatedHits(searchResult: SearchResult) = searchResult.estimatedHits
}