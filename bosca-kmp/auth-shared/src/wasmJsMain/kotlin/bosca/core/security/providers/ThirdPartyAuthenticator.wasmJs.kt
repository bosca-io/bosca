@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package bosca.core.security.providers

import bosca.core.security.ThirdPartyProvider
import bosca.core.security.ThirdPartyUser

actual object ThirdPartyAuthenticationProvider {

    actual suspend fun login(provider: ThirdPartyProvider): ThirdPartyUser {
        TODO("Not yet implemented")
    }
}