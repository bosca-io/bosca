package bosca.content.collection.graphql

import bosca.content.metadata.model.CollectionTemplateFilter
import bosca.content.metadata.model.CollectionTemplateFilters
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController


@TypeController
class CollectionTemplateFiltersController : GraphQLController<CollectionTemplateFilters> {

    @Field
    fun filters(filters: CollectionTemplateFilters): List<CollectionTemplateFilter> = filters.filters
}