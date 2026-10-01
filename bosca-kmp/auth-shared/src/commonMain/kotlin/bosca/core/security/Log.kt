package bosca.core.security

/**
 * Minimal internal diagnostic logger for the auth library. As a standalone module
 * `auth-shared` keeps no dependency on any host app, so it logs to the platform
 * console (`println` routes to Logcat on Android, the system console on Native,
 * and the browser console on JS/Wasm).
 *
 * These messages are diagnostics only — every auth failure is also surfaced to
 * callers as a thrown [BoscaAuthError] or an [bosca.core.security.model.AuthEvent.Error].
 */
internal object Log {
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            println("$message: ${throwable.message}")
        } else {
            println(message)
        }
    }
}
