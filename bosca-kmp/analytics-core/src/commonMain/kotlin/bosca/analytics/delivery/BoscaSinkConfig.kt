package bosca.analytics.delivery

import bosca.analytics.instrumentation.AutomaticInstrumentationOptions
import bosca.analytics.platform.currentAppVersion
import bosca.core.platform.providers.Urls
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Configuration for [BoscaSink]. */
data class BoscaSinkConfig(
    val url: String,
    val appId: String,
    val appVersion: String = currentAppVersion(),
    val clientId: String,
    val storageNamespace: String = "$appId-$clientId",
    val debug: Boolean = false,
    val anonymous: Boolean = false,
    val heartbeat: Boolean = true,
    val sessionTracking: Boolean = true,
    val heartbeatInterval: Duration = 15.minutes,
    val sessionTimeout: Duration = 5.minutes,
    val flushDelay: Duration = 1.seconds,
    val flushBatchSize: Int = 100,
    val autoFlush: Boolean = true,
    val automaticInstrumentation: AutomaticInstrumentationOptions = AutomaticInstrumentationOptions(),
) {
    /**
     * Creates app delivery configuration from the shared endpoint catalog. [Urls.analytics] is the
     * collector origin; this constructor selects its versioned event API and reads the installed
     * application version from platform package metadata.
     */
    constructor(
        urls: Urls,
        appId: String,
        clientId: String = "mobile",
    ) : this(
        url = collectorApiUrl(urls.analytics),
        appId = appId,
        clientId = clientId,
    )

    init {
        require(flushBatchSize > 0) { "Flush batch size must be positive" }
    }

    private companion object {
        fun collectorApiUrl(url: String): String = url.trimEnd('/').let { base ->
            if (base.endsWith("/api/v1")) base else "$base/api/v1"
        }
    }
}
