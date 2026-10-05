package bosca.content.timeevent.model

import kotlinx.serialization.Serializable

@Serializable
data class TimeEventFilter(
    val types: List<String>? = null,
    val startAfterMs: Long? = null,
    val endBeforeMs: Long? = null,
    val atOffsetMs: Long? = null
)
