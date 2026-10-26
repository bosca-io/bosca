package bosca.design.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** A chart or axis title. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChartTitle(
    val text: String,
    @EncodeDefault
    val display: Boolean = true,
    val color: String? = null,
)
