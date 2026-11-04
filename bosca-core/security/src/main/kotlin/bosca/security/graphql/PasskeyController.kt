package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.routes.passkey.PasskeyInfo

/** Public credential metadata returned by the authorized passkey list resolvers. */
@TypeController(type = "Passkey")
class PasskeyController : GraphQLController<PasskeyInfo> {
    @Field fun credentialId(passkey: PasskeyInfo) = passkey.credentialId
    @Field fun name(passkey: PasskeyInfo) = passkey.name
    @Field fun createdAt(passkey: PasskeyInfo) = passkey.createdAt
    @Field fun lastUsedAt(passkey: PasskeyInfo) = passkey.lastUsedAt
    @Field fun transports(passkey: PasskeyInfo) = passkey.transports
}
