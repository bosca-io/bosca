package bosca.ide.workops

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class BoscaDocumentMarkdownTest {
    @Test
    fun `renders common Bosca document nodes as editable markdown`() {
        val content = JsonParser.parseString(
            """
            {"document":{"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Plan"}]},
              {"type":"paragraph","content":[
                {"type":"text","text":"Ship","marks":[{"type":"bold"}]},
                {"type":"text","text":" it"}
              ]},
              {"type":"bulletList","content":[
                {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Test"}]}]}
              ]},
              {"type":"codeBlock","attrs":{"language":"kotlin"},"content":[{"type":"text","text":"fun main() {}"}]}
            ]}}
            """.trimIndent()
        )

        assertEquals(
            """
            ## Plan

            **Ship** it

            - Test

            ```kotlin
            fun main() {}
            ```
            """.trimIndent(),
            BoscaDocumentMarkdown.toMarkdown(content),
        )
    }

    @Test
    fun `empty content remains an empty markdown document`() {
        assertEquals("", BoscaDocumentMarkdown.toMarkdown(null))
    }
}
