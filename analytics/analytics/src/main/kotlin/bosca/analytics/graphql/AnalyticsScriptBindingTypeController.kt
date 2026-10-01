package bosca.analytics.graphql

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves fields on the `AnalyticsScriptBinding` GraphQL type. The framework uses explicit `@Field`
 * resolvers per type rather than reflective autobinding, so every schema property needs an entry.
 */
@TypeController(type = "AnalyticsScriptBinding")
class AnalyticsScriptBindingTypeController : GraphQLController<AnalyticsScriptBinding> {

    @Field
    fun id(binding: AnalyticsScriptBinding): UUID = binding.id

    @Field
    fun scriptId(binding: AnalyticsScriptBinding): UUID = binding.scriptId

    @Field
    fun transform(binding: AnalyticsScriptBinding): Boolean = binding.transform

    @Field
    fun enabled(binding: AnalyticsScriptBinding): Boolean = binding.enabled

    @Field
    fun ordinal(binding: AnalyticsScriptBinding): Int = binding.ordinal

    @Field
    fun created(binding: AnalyticsScriptBinding): OffsetDateTime = binding.created

    @Field
    fun modified(binding: AnalyticsScriptBinding): OffsetDateTime = binding.modified
}
