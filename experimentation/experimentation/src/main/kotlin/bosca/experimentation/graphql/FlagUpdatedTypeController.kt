package bosca.experimentation.graphql

import bosca.experimentation.model.FlagUpdateAction
import bosca.experimentation.model.FlagUpdated
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/**
 * Resolves fields on the FlagUpdated GraphQL type, which is published via
 * subscription when a feature flag is modified. Explicit `@Field` resolvers
 * are required because the framework does not autobind type fields from
 * data class properties.
 */
@TypeController(type = "FlagUpdated")
class FlagUpdatedTypeController : GraphQLController<FlagUpdated> {

    @Field
    fun flagKey(update: FlagUpdated): String = update.flagKey

    @Field
    fun flagId(update: FlagUpdated): UUID = update.flagId

    @Field
    fun action(update: FlagUpdated): FlagUpdateAction = update.action
}
