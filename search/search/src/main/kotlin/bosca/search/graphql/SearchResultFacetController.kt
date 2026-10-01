package bosca.search.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.search.model.SearchResultFacet

@TypeController
class SearchResultFacetController : GraphQLController<SearchResultFacet> {

    @Field
    fun count(facet: SearchResultFacet) = facet.count

    @Field
    fun field(facet: SearchResultFacet) = facet.field

    @Field
    fun value(facet: SearchResultFacet) = facet.value
}