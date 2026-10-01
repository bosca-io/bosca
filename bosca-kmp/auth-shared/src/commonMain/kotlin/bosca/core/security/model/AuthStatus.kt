package bosca.core.security.model

/**
 * The observable authentication state exposed by `BoscaAuth`.
 *
 * [Unknown] is the pre-`initialize()` state — navigation must wait on it rather
 * than treating the user as signed out (it replaces the old `isVerified == null`
 * loading sentinel). Once initialization resolves, the state is exactly one of
 * [Authenticated] or [Unauthenticated].
 */
sealed interface AuthStatus {
    /** Initial state before `initialize()` has resolved a stored session. */
    data object Unknown : AuthStatus

    /** A user has a server-verified session, possibly awaiting refresh while offline. */
    data class Authenticated(val principal: Principal) : AuthStatus

    /** No session exists. */
    data object Unauthenticated : AuthStatus
}

/**
 * One-shot authentication events. Port of the TS event emitter
 * (`signedIn`/`signedOut`/`tokenRefreshed`/`profileUpdated`/`error`), surfaced
 * as an idiomatic Kotlin event stream instead of `on`/`off` callbacks.
 */
sealed interface AuthEvent {
    data class SignedIn(val response: AuthResponse) : AuthEvent
    data object SignedOut : AuthEvent
    data class TokenRefreshed(val response: AuthResponse) : AuthEvent
    data class ProfileUpdated(val profiles: List<Profile>) : AuthEvent
    data class Error(val error: Throwable) : AuthEvent
}
