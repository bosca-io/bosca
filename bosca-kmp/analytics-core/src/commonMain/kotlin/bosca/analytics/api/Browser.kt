package bosca.analytics.api

import kotlinx.serialization.Serializable

/** Browser data retained for collector compatibility. */
@Serializable
data class Browser(val agent: String)
