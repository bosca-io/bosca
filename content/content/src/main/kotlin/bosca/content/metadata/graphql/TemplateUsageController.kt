package bosca.content.metadata.graphql

import bosca.content.metadata.model.TemplateUsage
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class TemplateUsageController : GraphQLController<TemplateUsage> {

    @Field
    fun items(usage: TemplateUsage) = usage.items

    @Field
    fun collections(usage: TemplateUsage) = usage.collections

    @Field
    fun templates(usage: TemplateUsage) = usage.templates

    @Field
    fun total(usage: TemplateUsage) = usage.total
}
