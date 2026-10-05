package bosca.analytics.model

import kotlinx.serialization.Serializable

/**
 * A single active session on the live map: one visitor at a coarse, IP-derived location, tagged with
 * the application and version they are on. Positions come from Cloudflare's city-centroid geo (see
 * [Geo]), so they are already coarse — no precise geolocation is captured. `sessionId` is the
 * client-side render key; it is not used to de-duplicate server-side (the stream's retained contents
 * ARE the active set).
 *
 * `appId` is carried in the payload (not just the transport subject/key) so an all-applications
 * subscription can attribute each point to its app; it is nullable because heartbeats retained from
 * before the field existed decode without one.
 */
@Serializable
data class LiveSession(
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val appId: String? = null,
    val appVersion: String? = null,
)
