package bosca.analytics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Geo(
    val city: String? = null,
    val country: String? = null,
    val continent: String? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val region: String? = null,
    @SerialName("region_code")
    val regionCode: String? = null,
    @SerialName("postal_code")
    val postalCode: String? = null,
    val timezone: String? = null
)