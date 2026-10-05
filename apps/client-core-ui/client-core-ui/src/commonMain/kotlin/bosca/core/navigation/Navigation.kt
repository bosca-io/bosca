package bosca.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
abstract class Route : NavKey

@SerialName("onboarding")
@Serializable
data object Onboarding : Route()

@SerialName("login")
@Serializable
data object Login : Route()

@SerialName("register")
@Serializable
data object Register : Route()

@SerialName("verify")
@Serializable
data object Verify : Route()

@SerialName("forgot_password")
@Serializable
data object ForgotPassword : Route()

@SerialName("reset_password")
@Serializable
data class ResetPassword(val token: String) : Route()

@SerialName("home")
@Serializable
data object Home : Route()
