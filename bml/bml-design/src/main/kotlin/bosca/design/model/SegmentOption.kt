package bosca.design.model

import kotlinx.serialization.Serializable

/** One native form choice, with a stable value independent of its display label. */
@Serializable
data class SegmentOption(val value: String, val label: String, val icon: String = "", val disabled: Boolean = false)
