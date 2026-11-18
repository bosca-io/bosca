package bosca.design.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Native Apache ECharts options, serialized as the option object without an extra envelope. */
@JvmInline
@Serializable
value class ChartConfiguration(val option: JsonObject)
