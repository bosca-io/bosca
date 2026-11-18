package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** A node position in canvas coordinates. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FlowPosition(@EncodeDefault val x: Double = 0.0, @EncodeDefault val y: Double = 0.0)
