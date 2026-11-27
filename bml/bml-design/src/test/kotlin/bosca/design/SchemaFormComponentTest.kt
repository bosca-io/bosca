package bosca.design

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.render.RenderContext
import bosca.design.generated.BmlComponents
import bosca.design.model.SchemaFormOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class SchemaFormComponentTest {
    @Test
    fun `compiled library renders stored layouts with shared controls and escaped values`() = runBlocking {
        val tag = BmlLibraryArtifacts.componentId("io.bosca:bml-design", "schema-form")
        val renderer = requireNotNull(BmlComponents.renderers[tag])
        val context = RenderContext()
        renderer.render(context, mapOf(
            "id" to "form-test",
            "schema" to Json.parseToJsonElement("""{"type":"object","properties":{"summary":{"type":"string"},"owner":{"type":"string"}},"required":["summary"]}""").jsonObject,
            "uiSchema" to Json.parseToJsonElement("""{"version":1,"layout":[{"type":"section","label":"<Planning>","children":[{"type":"row","children":[{"type":"field","property":"summary","control":"text-input","col":6},{"type":"field","property":"owner","control":"domain-owner","col":6}]}]}]}""").jsonObject,
            "value" to buildJsonObject { put("summary", "<script>escaped</script>") },
            "controlAliases" to mapOf("domain-owner" to "select"),
            "options" to mapOf("owner" to listOf(SchemaFormOption("first", "<First owner>"))),
        )) {}
        val html = context.writer.toString()
        assertContains(html, "data-bml-schema-form")
        assertContains(html, "&lt;Planning&gt;")
        assertContains(html, "schema-row")
        assertContains(html, "span-6")
        assertContains(html, "data-schema-control=\"select\"")
        assertContains(html, "&lt;First owner&gt;")
        assertContains(html, "&lt;script&gt;escaped&lt;/script&gt;")
        assertFalse("<script>escaped</script>" in html)
        assertContains(html, "name=\"attributes\"")
        assertContains(html, " required")
    }
}
