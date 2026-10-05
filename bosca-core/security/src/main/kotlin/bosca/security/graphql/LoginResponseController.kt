package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.security.model.LoginResponse
import bosca.security.service.SecurityService


@TypeController
class LoginResponseController(
    private val securityService: SecurityService,
    private val profileService: ProfileService
) : GraphQLController<LoginResponse> {

    @Field
    suspend fun principal(
        response: LoginResponse,
    ) = securityService.getPrincipalById(response.principalId)

    @Field
    suspend fun profile(
        response: LoginResponse,
    ) = profileService.getByPrincipal(response.principalId)

    @Field
    fun refreshToken(response: LoginResponse) = response.refreshToken

    @Field
    fun token(response: LoginResponse) = response.token

    @Field
    fun accountCreated(response: LoginResponse) = response.accountCreated

    @Field
    fun originator(response: LoginResponse) = response.originator
}
