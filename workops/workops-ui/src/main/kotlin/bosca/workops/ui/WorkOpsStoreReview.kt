package bosca.workops.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Renders vendor-authored review text as plain text and preserves unknown ratings. */
@Serializable
data class WorkOpsStoreReview(val payload: JsonObject) {
    val rating get() = (payload["rating"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    val body get() = (payload["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: "No review text"
    val ratingLabel get() = rating?.let { workOpsStoreNumber(it, 2) } ?: "Unavailable"
}
