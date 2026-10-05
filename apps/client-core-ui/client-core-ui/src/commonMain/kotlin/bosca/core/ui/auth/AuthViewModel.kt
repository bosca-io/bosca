package bosca.core.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bosca.core.Res
import bosca.core.login_error_default
import bosca.core.security.BoscaAuth
import bosca.core.security.EmailNotVerifiedError
import bosca.core.security.NetworkError
import bosca.core.security.PrincipalNotVerifiedError
import bosca.core.security.ThirdPartyProvider
import bosca.core.security.model.AuthStatus
import bosca.core.security.model.ProfileInput
import bosca.core.security.model.SignupOptions
import bosca.core.signup_error_default
import bosca.core.verify_error_default
import bosca.core.verify_refresh_error_default
import bosca.core.verify_resend_error_default
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.compose.resources.getString

/**
 * ViewModel for the unauthenticated auth flows (login, signup, email
 * verification, native OAuth). Thin wrapper over [BoscaAuth]; the durable auth
 * state lives in the [BoscaAuth] singleton, so this ViewModel can be recreated
 * freely.
 *
 * Exposes [isLoggedIn]/[isVerified] derived from [BoscaAuth.status] so the
 * navigation graph keeps its existing tri-state contract ([isVerified] is null
 * while the session is still being restored).
 */
class AuthViewModel(private val auth: BoscaAuth) : ViewModel() {

    val isLoggedIn: StateFlow<Boolean> = auth.status
        .map { it is AuthStatus.Authenticated }
        .stateIn(viewModelScope, SharingStarted.Eagerly, auth.status.value is AuthStatus.Authenticated)

    val isVerified: StateFlow<Boolean?> = auth.status
        .map { it.toVerified() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, auth.status.value.toVerified())

    /** The identifier the user last attempted to authenticate with (for the verify screen). */
    private val _identifier = MutableStateFlow<String?>(null)
    val identifier: StateFlow<String?> = _identifier.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            // Restore a persisted session on startup; resolves status away from Unknown.
            runCatching { auth.initialize() }
        }
    }

    fun login(provider: ThirdPartyProvider, languageTag: String) {
        launchGuarded(Res.string.login_error_default) {
            auth.signInWithThirdPartyNative(provider, languageTag)
            _events.emit(AuthEvent.LoginSuccess)
        }
    }

    fun login(identifier: String, password: String) {
        _identifier.value = identifier
        launchGuarded(
            Res.string.login_error_default,
            // A correct password on an unverified account is blocked by the backend gate
            // (PrincipalNotVerifiedError; EmailNotVerifiedError is its email-specific sibling). Route the user
            // to verification — where they can resend the link — instead of a dead-end error, mirroring web.
            onError = { e ->
                if (e is PrincipalNotVerifiedError || e is EmailNotVerifiedError) AuthEvent.VerificationRequired else null
            },
        ) {
            auth.signInWithPassword(identifier, password)
            _events.emit(AuthEvent.LoginSuccess)
        }
    }

    fun signup(identifier: String, password: String, profile: ProfileInput, languageTag: String) {
        _identifier.value = identifier
        launchGuarded(Res.string.signup_error_default) {
            auth.signUp(SignupOptions(identifier, password, profile, languageTag))
            _events.emit(AuthEvent.SignupSuccess)
        }
    }

    fun verify(token: String) {
        launchGuarded(Res.string.verify_error_default) {
            auth.verifyEmail(token)
            // Re-fetch the principal so `verified` (and navigation) update.
            auth.initialize()
            _events.emit(AuthEvent.VerifySuccess)
        }
    }

    fun resendVerification(identifier: String) {
        launchGuarded(Res.string.verify_resend_error_default) {
            auth.resendVerification(identifier)
        }
    }

    fun refreshVerificationStatus() {
        launchGuarded(Res.string.verify_refresh_error_default) {
            auth.initialize()
        }
    }

    fun forgotPassword(identifier: String) {
        launchGuarded(Res.string.login_error_default) {
            auth.forgotPassword(identifier)
            _events.emit(AuthEvent.ForgotPasswordSent)
        }
    }

    fun resetPassword(token: String, password: String) {
        launchGuarded(Res.string.login_error_default) {
            auth.resetPassword(token, password)
            _events.emit(AuthEvent.PasswordReset)
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                auth.signOut()
            } catch (e: NetworkError) {
                _events.emit(AuthEvent.Error(e.message ?: "Sign out failed"))
                return@launch
            }
            _events.emit(AuthEvent.Logout)
        }
    }

    /**
     * Runs [block] with loading state. On failure, [onError] gets first refusal to map the exception to a
     * specific [AuthEvent] (e.g. routing an unverified login to verification); when it returns null the failure
     * falls back to a generic [AuthEvent.Error] carrying the exception message or [defaultError].
     * [CancellationException] is re-thrown so coroutine cancellation is never masked as an error event.
     */
    private fun launchGuarded(
        defaultError: org.jetbrains.compose.resources.StringResource,
        onError: (Throwable) -> AuthEvent? = { null },
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _events.emit(onError(e) ?: AuthEvent.Error(e.message ?: getString(defaultError)))
            } finally {
                _isLoading.value = false
            }
        }
    }

    sealed class AuthEvent {
        data object LoginSuccess : AuthEvent()
        /** A login was rejected because the account isn't verified yet — the UI should route to verification. */
        data object VerificationRequired : AuthEvent()
        data object SignupSuccess : AuthEvent()
        data object VerifySuccess : AuthEvent()
        data object ForgotPasswordSent : AuthEvent()
        data object PasswordReset : AuthEvent()
        data object Logout : AuthEvent()
        data class Error(val message: String) : AuthEvent()
    }

    private companion object {
        fun AuthStatus.toVerified(): Boolean? = when (this) {
            AuthStatus.Unknown -> null
            is AuthStatus.Authenticated -> principal.verified
            AuthStatus.Unauthenticated -> false
        }
    }
}
