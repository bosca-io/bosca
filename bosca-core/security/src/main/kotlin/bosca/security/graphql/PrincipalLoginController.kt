package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PrincipalLogin
import bosca.security.service.AuthenticationContext

/** Resolves the public fields of a successful principal authentication record. */
@TypeController
class PrincipalLoginController : GraphQLController<PrincipalLogin> {

    @Field
    fun id(login: PrincipalLogin) = login.id

    @Field
    fun method(login: PrincipalLogin) = login.method

    @Field
    fun revokedAt(login: PrincipalLogin) = login.revokedAt

    @Field
    fun current(authentication: AuthenticationContext?, login: PrincipalLogin): Boolean =
        authentication?.principal()?.loginId == login.id

    @Field
    fun created(login: PrincipalLogin) = login.created
}
