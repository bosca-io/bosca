package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Token

@TypeController
class TokenController : GraphQLController<Token> {

    @Field
    fun expiresAt(token: Token) = token.expiresAt

    @Field
    fun issuedAt(token: Token) = token.issuedAt

    @Field
    fun token(token: Token) = token.token
}