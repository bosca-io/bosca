package bosca.experimentation.model

import kotlinx.serialization.Serializable

/** A displayed interval, on the same scale as its estimate. */
@Serializable
data class MetricInterval(val lower: Double, val upper: Double)
