package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** The canvas translation and zoom retained with a graph. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FlowViewport(@EncodeDefault val x: Double = 0.0, @EncodeDefault val y: Double = 0.0, @EncodeDefault val zoom: Double = 1.0)
