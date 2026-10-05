package bosca.graphql.client

/** Identifies the Bosca application installation and optional analytics session issuing a GraphQL request. */
data class BoscaGraphQLClientInfo(
    val installationId: String,
    val appId: String,
    val appVersion: String,
    val sessionId: String? = null,
) {
    /** Creates installation identity when analytics session tracking is unavailable. */
    constructor(installationId: String, appId: String, appVersion: String) :
        this(installationId, appId, appVersion, null)

    init {
        require(installationId.isNotBlank()) { "Installation ID must not be blank" }
        require(appId.isNotBlank()) { "App ID must not be blank" }
        require(appVersion.isNotBlank()) { "App version must not be blank" }
    }

    internal fun headers(): Map<String, String> = mapOf(
        BoscaGraphQLHeaders.INSTALLATION_ID to installationId,
        BoscaGraphQLHeaders.APP_ID to appId,
        BoscaGraphQLHeaders.APP_VERSION to appVersion,
    ) + (sessionId?.takeIf { it.isNotBlank() }?.let { mapOf(BoscaGraphQLHeaders.SESSION_ID to it) } ?: emptyMap())
}

/** Standard request headers emitted by Bosca GraphQL clients. */
object BoscaGraphQLHeaders {
    const val INSTALLATION_ID = "X-Installation-ID"
    const val APP_ID = "X-App-ID"
    const val APP_VERSION = "X-App-Version"
    const val SESSION_ID = "X-BA-Session-ID"
}
