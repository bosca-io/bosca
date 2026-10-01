package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.LinkChallenge
import bosca.security.model.LoginResponse
import bosca.security.model.Principal
import bosca.security.model.SignupResult

@TypeController
class SignupResultController : GraphQLController<SignupResult> {

    @Field
    fun principal(signup: SignupResult): Principal? = signup.principal

    @Field
    fun loginResponse(signup: SignupResult): LoginResponse? = signup.loginResponse

    @Field
    fun linkChallenge(signup: SignupResult): LinkChallenge? = signup.linkChallenge
}
