package bosca.analytics.server

/** Analytics identity and additional event context propagated through a coroutine scope. */
data class AnalyticsContext(
    val appId: String? = null,
    val appVersion: String? = null,
    val installationId: String? = null,
    val sessionId: String? = null,
    val userId: String? = null,
    val extras: Map<String, Any?> = emptyMap(),
) {
    /** Returns context keys with non-null typed identity taking precedence. */
    fun toMap(): Map<String, Any?> = toMap(false)

    /** Full replacements include null identity so lower-priority sources cannot restore cleared fields. */
    internal fun toMap(includeNullIdentity: Boolean): Map<String, Any?> = buildMap {
        putAll(extras)
        if (includeNullIdentity) {
            put("app_id", appId)
            put("app_version", appVersion)
            put("installation_id", installationId)
            put("session_id", sessionId)
            put("user_id", userId)
        }
        appId?.let { put("app_id", it) }
        appVersion?.let { put("app_version", it) }
        installationId?.let { put("installation_id", it) }
        sessionId?.let { put("session_id", it) }
        userId?.let { put("user_id", it) }
    }

    companion object {
        /** Reads standard identity keys and preserves the remaining entries as additional context. */
        fun fromEntries(entries: Map<String, Any?>): AnalyticsContext = AnalyticsContext(
            appId = entries["app_id"] as String?,
            appVersion = entries["app_version"] as String?,
            installationId = entries["installation_id"] as String?,
            sessionId = entries["session_id"] as String?,
            userId = entries["user_id"] as String?,
            // Explicit nulls must survive conversion so they can clear identity from lower-priority sources.
            extras = entries.filter { (key, value) -> key !in IDENTITY_KEYS || value == null },
        )

        private val IDENTITY_KEYS = setOf("app_id", "app_version", "installation_id", "session_id", "user_id")
    }
}
