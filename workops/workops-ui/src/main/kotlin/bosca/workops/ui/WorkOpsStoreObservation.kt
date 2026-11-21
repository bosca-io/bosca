package bosca.workops.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.OffsetDateTime

/** Store payload values remain typed JSON; a missing or nonnumeric metric is not zero. */
@Serializable
data class WorkOpsStoreObservation(
    val store: String,
    val applicationId: String,
    val appVersion: String,
    val type: String,
    val time: String,
    val payload: JsonElement,
) {
    val instant: Instant get() = OffsetDateTime.parse(time).toInstant()
    fun number(key: String): Double? = ((payload as? JsonObject)?.get(key) as? JsonPrimitive)
        ?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    val reviews get() = ((payload as? JsonObject)?.get("reviews") as? JsonArray).orEmpty()
        .filterIsInstance<JsonObject>().map { WorkOpsStoreReview(it) }
}
