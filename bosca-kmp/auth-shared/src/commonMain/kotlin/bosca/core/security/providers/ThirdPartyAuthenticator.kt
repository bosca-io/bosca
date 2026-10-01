@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package bosca.core.security.providers

import bosca.core.security.ThirdPartyProvider
import bosca.core.security.ThirdPartyUser

expect object ThirdPartyAuthenticationProvider {

    suspend fun login(provider: ThirdPartyProvider): ThirdPartyUser
}
