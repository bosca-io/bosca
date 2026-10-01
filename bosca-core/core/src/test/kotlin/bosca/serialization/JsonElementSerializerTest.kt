package bosca.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonElementSerializerTest {

    @Test
    fun descriptorName() {
        val serializer = JsonElementSerializer()
        assertEquals("Json", serializer.descriptor.serialName)
    }
}
