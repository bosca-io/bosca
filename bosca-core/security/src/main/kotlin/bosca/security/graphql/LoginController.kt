package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.LoginResponse
import bosca.security.model.SimplePasswordAttributes
import bosca.security.service.SecurityService

object Login

@TypeController
class LoginController(
    private val securityService: SecurityService
) : GraphQLController<Login> {

    @Field
    suspend fun password(
        identifier: String,
        password: String
    ): LoginResponse {
        return securityService.loginWithCredential(
            SimplePasswordAttributes(
                identifier = identifier,
                password = password
            )
        )
    }
}
