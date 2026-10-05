package bosca.core.security.model

/**
 * Configuration for `BoscaAuth`, controlling the API endpoint and automatic
 * refresh behavior. Port of `BoscaAuthConfig` from types.ts, minus the
 * web-only `storage`/`cookieDomain` knobs — on KMP the storage strategy is
 * supplied by the platform-injected `TokenStorage`.
 *
 * Durations are expressed in **milliseconds** (the TS uses `number` ms).
 */
data class BoscaAuthConfig(
    /** Base URL of the Bosca API; used to build OAuth redirect URLs. */
    val apiUrl: String,
    /**
     * Absolute URL of the GraphQL endpoint the hand-rolled
     * [bosca.core.security.AuthHttpClient] POSTs to. When null the client
     * derives `"$apiUrl/graphql"`; set it explicitly when GraphQL is served from
     * a different host/path than [apiUrl].
     */
    val graphqlUrl: String? = null,
    /** Storage key prefix for the access token (default `_bat`). */
    val tokenName: String = "_bat",
    /** Milliseconds before token expiry to trigger a proactive refresh. */
    val refreshBufferMillis: Long = 60_000L,
    /** Whether to automatically refresh tokens before they expire. */
    val autoRefresh: Boolean = true,
    /** Milliseconds to wait before retrying a failed token refresh. */
    val retryDelayMillis: Long = 1_000L,
    /** Fallback BCP 47 language tag when the platform locale is unavailable. */
    val defaultLanguageTag: String? = null,
)
