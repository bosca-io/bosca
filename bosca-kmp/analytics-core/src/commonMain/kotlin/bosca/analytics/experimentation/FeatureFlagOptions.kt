package bosca.analytics.experimentation

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Feature-flag cache identity and subscription retry configuration. */
data class FeatureFlagOptions(
    val identity: suspend () -> String? = { null },
    val maxReconnectAttempts: Int = 20,
    val baseReconnectDelay: Duration = 1.seconds,
    val maxReconnectDelay: Duration = 60.seconds,
)
