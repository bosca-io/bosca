package bosca.messages.pages.emails

import bosca.security.service.AppOrigins
import bosca.server.config.ApplicationConfig

/**
 * Resolves the validated web origin for a transactional-email link from a stored (raw) [storedOrigin] — the
 * host the user came from when it is allow-listed (`oauth2.redirects`), otherwise the default `app.url`.
 *
 * Validation happens here, at link-build time, so the raw origin persisted with the token can never be used
 * to point an email link at an un-allow-listed (e.g. attacker) host.
 */
fun ApplicationConfig.appOriginFor(storedOrigin: String?): String =
    AppOrigins.resolve(
        explicit = null,
        requestOrigin = storedOrigin,
        allowed = propertyOrNull("app.allowed-origins")?.getList() ?: emptyList(),
        default = property("app.url").getString(),
    )
