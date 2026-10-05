@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package bosca.core.security.providers

import bosca.core.security.type.ThirdPartyType
import bosca.core.security.ThirdPartyProvider
import bosca.core.security.ThirdPartyUser
import kotlinx.cinterop.ExperimentalForeignApi

actual object ThirdPartyAuthenticationProvider {

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun login(provider: ThirdPartyProvider): ThirdPartyUser {
        val controller = IosAuthPresenter.controllerProvider?.invoke()
            ?: error("No presenting UIViewController available; set IosAuthPresenter.controllerProvider during app init")

        return when (provider) {
            ThirdPartyProvider.GOOGLE -> {
                val result = controller.loginWithGoogle() ?: error("No result from Google sign-in")
                ThirdPartyUser(
                    type = ThirdPartyType.GOOGLE,
                    id = result.user.userID ?: error("No user ID"),
                    name = result.user.profile?.name ?: error("No name"),
                    email = result.user.profile?.email ?: error("No email"),
                    picture = result.user.profile?.imageURLWithDimension(512u)?.absoluteString,
                    token = result.user.idToken?.tokenString ?: error("No token")
                )
            }

            else -> TODO("Unsupported third-party provider: $provider")
        }
    }
}