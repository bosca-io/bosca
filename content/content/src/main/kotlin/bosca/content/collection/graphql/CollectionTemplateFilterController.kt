package bosca.content.collection.graphql

import bosca.content.metadata.model.CollectionTemplateFilter
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class CollectionTemplateFilterController : GraphQLController<CollectionTemplateFilter> {

    @Field
    fun name(filter: CollectionTemplateFilter) = filter.name

    @Field
    fun filter(filter: CollectionTemplateFilter) = filter.filter
}