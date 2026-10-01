package bosca.security.service

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds the Studio web-app URLs embedded in transactional auth emails (verification,
 * password reset, account-link confirmation).
 *
 * The route paths mirror the Studio (Nuxt) routes exactly and live here as the single
 * source of truth. Callers supply only the app's public origin — the `app.url` config,
 * e.g. `http://localhost:3000` in dev or `https://admin.example.com` in production — so
 * a frontend route rename is a one-line change here instead of editing several
 * per-environment URL templates, and the paths can never drift between email types.
 */
object AuthWebLinks {

    /** `/auth/verify?token=…` — the email-verification landing page. */
    fun verify(appUrl: String, token: String): String =
        "${origin(appUrl)}/auth/verify?token=${encode(token)}"

    /** `/auth/reset-password?token=…` — the password-reset page. */
    fun resetPassword(appUrl: String, token: String): String =
        "${origin(appUrl)}/auth/reset-password?token=${encode(token)}"

    /** `/auth/link/confirm?proof=…` — the account-link email-proof magic link. */
    fun accountLink(appUrl: String, proofToken: String): String =
        "${origin(appUrl)}/auth/link/confirm?proof=${encode(proofToken)}"

    /** Strips a trailing slash so `${origin}/auth/...` never doubles up the separator. */
    private fun origin(appUrl: String): String = appUrl.trimEnd('/')

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
