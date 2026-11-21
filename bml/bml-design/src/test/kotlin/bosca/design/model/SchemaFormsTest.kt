package bosca.design.model

import kotlinx.serialization.json.*
import kotlin.test.*

class SchemaFormsTest {
    private fun obj(source: String) = Json.parseToJsonElement(source).jsonObject

    @Test
    fun `stored layouts preserve nested requirements defaults supplied values and domain options`() {
        val schema = obj("""{"type":"object","properties":{"title":{"type":"string","default":"Draft"},"active":{"type":"boolean","default":true},"owner":{"type":"string"},"address":{"type":"object","properties":{"city":{"type":"string","default":"Home"}},"required":["city"]}},"required":["title"]}""")
        val layout = obj("""{"version":1,"layout":[{"type":"section","label":"General","children":[{"type":"row","children":[{"type":"field","property":"title","control":"text-input","col":6},{"type":"field","property":"active","control":"switch","col":6}]},{"type":"field","property":"owner","control":"product-owner"},{"type":"field","property":"address.city","control":"text-input"}]}]}""")
        val value = obj("""{"active":false,"address":{"city":"Away"},"hidden":"retain me"}""")
        val defaults = schemaFormDefaults(schema, value)
        assertEquals(JsonPrimitive(false), defaults["active"])
        assertEquals(JsonPrimitive("Draft"), defaults["title"])
        assertEquals(JsonPrimitive("retain me"), defaults["hidden"])
        assertEquals(JsonPrimitive("Home"), schemaFormDefaults(schema, obj("{}")).getValue("address").jsonObject["city"])
        assertEquals(JsonNull, schemaFormDefaults(schema, obj("""{"address":null}"""))["address"])
        val section = schemaFormNodes(schema, layout, value,
            options = mapOf("owner" to listOf(SchemaFormOption("one", "First owner"))),
            controlAliases = mapOf("product-owner" to "select"), errors = mapOf("owner" to "Choose an owner"))
            .single()
        val row = section.children.first()
        assertTrue(row.children.first().required)
        assertEquals(6, row.children.first().columns)
        assertFalse(row.children.last().checked)
        val owner = section.children[1]
        assertEquals("select", owner.control)
        assertEquals("First owner", owner.options.single().label)
        assertEquals("Choose an owner", owner.error)
        assertEquals("Away", section.children.last().text)
        assertTrue(section.children.last().required)
    }

    @Test
    fun `nested tab boundaries escape authored labels and include every field exactly once`() {
        val schema = obj("""{"properties":{"title":{"type":"string"}}}""")
        val ui = obj("""{"version":1,"layout":[{"type":"tabs","children":[{"type":"tab","label":"<first>","children":[{"type":"section","label":"<script>","children":[{"type":"field","property":"title","control":"text-input"}]}]},{"type":"tab","label":"Second","children":[]}]}]}""")
        val parts = schemaFormParts(schemaFormNodes(schema, ui))
        val markup = parts.joinToString("") { it.markup }
        assertContains(markup, "aria-label=\"&lt;first&gt;\"")
        assertContains(markup, "<h3>&lt;script&gt;</h3>")
        assertContains(markup, "role=\"group\" aria-label=\"&lt;script&gt;\"")
        assertContains(markup, " hidden>")
        assertFalse("<script>" in markup)
        assertEquals(1, parts.count { it.node.type == "field" })
        assertEquals(1, parts.count { it.node.type == "tabs" })
    }

    @Test
    fun `unavailable schemas report their actual defect`() {
        assertFailsWith<IllegalArgumentException> { schemaFormNodes(obj("{}"), obj("""{"version":2,"layout":[]}""")) }
        val failure = assertFailsWith<IllegalStateException> {
            schemaFormNodes(obj("{}"), obj("""{"version":1,"layout":[{"type":"field","property":"missing","control":"text-input"}]}"""))
        }
        assertContains(failure.message.orEmpty(), "missing")
    }
}
