package bosca.bml.codegen

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BmlLibraryStylesTest {
    @Test
    fun `stylesheet extraction ignores installed packages beneath authored BML sources`() {
        val work = createTempDirectory("bml-style-discovery").toFile()
        try {
            val root = File(work, "markup").apply { mkdirs() }
            for (path in listOf("button.bml", "node_modules-example/badge.bml",
                "node_modules/token/example.bml", "controls/node_modules/@design/token/example.bml")) {
                File(root, path).apply {
                    parentFile.mkdirs()
                    writeText("""<component tag="sample"><style scoped>@import './theme.css';</style><span/></component>""")
                }
            }
            val output = File(work, "styles/inputs.json")
            BmlLibraryStyles.main(arrayOf(root.absolutePath, output.absolutePath))
            val styles = Json.parseToJsonElement(output.readText()).jsonArray
            assertEquals(setOf("button.bml", "node_modules-example/badge.bml"),
                styles.map { it.jsonObject.getValue("source").jsonPrimitive.content }.toSet())
            assertEquals(listOf("@import './theme.css';", "@import './theme.css';"),
                styles.map { it.jsonObject.getValue("css").jsonPrimitive.content })
        } finally {
            work.deleteRecursively()
        }
    }
}
