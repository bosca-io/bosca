package bosca.security.session

import bosca.security.model.LoginResponse
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class Session(val token: String) {

    constructor(response: LoginResponse, admin: Boolean) : this(
        if (admin) {
            "admin:::${response.token.token}"
        } else {
            response.token.token
        }
    )
}
