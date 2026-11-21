package bosca.design.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Bundled geographic boundaries for native chart maps; applications own their values and transport. */
object ChartMaps {
    /** Natural Earth country boundaries, packaged with the library without runtime network access. */
    val world: JsonObject by lazy {
        val source = requireNotNull(ChartMaps::class.java.getResourceAsStream("/maps/world.geojson")) { "Missing world map boundaries." }
        source.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
}
