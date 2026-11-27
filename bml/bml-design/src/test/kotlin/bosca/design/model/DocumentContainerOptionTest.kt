package bosca.design.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentContainerOptionTest {
    @Test
    fun `presentation defaults and authored labels reach the public client contract`() {
        val choice = Json.parseToJsonElement(documentContainerChoices(listOf(DocumentContainerOption("main", "Main content"))))
            .jsonArray.single().jsonObject
        assertEquals("main", choice.getValue("id").jsonPrimitive.content)
        assertEquals("Main content", choice.getValue("name").jsonPrimitive.content)
        assertEquals("", choice.getValue("description").jsonPrimitive.content)
        assertEquals("STANDARD", choice.getValue("type").jsonPrimitive.content)
        assertEquals("[]", choice.getValue("filters").toString())
    }
}
