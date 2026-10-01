package bosca.devices.graphql

import bosca.devices.model.PushToken
import bosca.devices.model.PushProvider
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime

/**
 * Resolves fields on the PushToken GraphQL type.
 */
@TypeController
class PushTokenController : GraphQLController<PushToken> {

    @Field
    fun provider(pushToken: PushToken): PushProvider = pushToken.provider

    @Field
    fun token(pushToken: PushToken): String {
        val t = pushToken.token
        return if (t.length > 8) "${"*".repeat(t.length - 8)}${t.takeLast(8)}" else t
    }

    @Field
    fun created(pushToken: PushToken): OffsetDateTime = pushToken.created
}
