package bosca.design.model

import kotlinx.serialization.json.*
import kotlin.test.*

class ChartMapsTest {
    @Test fun `world geometry is a bundled country collection usable without catalog resources`() {
        val world = ChartMaps.world
        assertEquals("FeatureCollection", world.getValue("type").jsonPrimitive.content)
        val features = world.getValue("features").jsonArray
        assertTrue(features.size > 100)
        assertTrue(features.any { it.jsonObject.getValue("properties").jsonObject["name"]?.jsonPrimitive?.content == "United States of America" })
        assertTrue(features.all { it.jsonObject.getValue("geometry").jsonObject.getValue("type").jsonPrimitive.content in listOf("Polygon", "MultiPolygon") })
        assertSame(world, ChartMaps.world)
    }
}
