package bosca.ai.kit.tools.document

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.content.metadata.service.BibleService
import bosca.documents.Content
import bosca.documents.DocumentSerializers
import bosca.documents.HtmlNode
import bosca.documents.NodeConverter
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Converts authored HTML into a Bosca tiptap [Content] document. This is how the
 * document-writer turns prose into the platform document model: the LLM writes HTML (which
 * LLMs do well), and [NodeConverter] deterministically produces the tiptap tree — no
 * brittle structured-JSON authoring. `<sup>` becomes a superscript mark.
 */
class ConvertToDocumentTool(
    private val bibleService: BibleService,
) : KitTool<ConvertToDocumentTool.Input, ConvertToDocumentTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "document.convert",
    description = "Convert HTML into a Bosca Content document. Takes an HTML string and transforms it into the structured document format (Content JSON) used by Bosca. Supported HTML elements: h1-h6, p, ul, ol, li, blockquote, strong, em, a, img, hr, br, sup, div, section, span.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The HTML string to convert into a Bosca Content document")
        val html: String,
    )

    @Serializable
    data class Output(
        val document: String,
        val success: Boolean,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        return try {
            val converter = NodeConverter(bibleService)
            val documentNode = converter.convertDocument(HtmlNode(html = input.html))
            val content = Content(document = documentNode)
            Output(
                document = documentJson.encodeToString(Content.serializer(), content),
                success = true,
            )
        } catch (e: Exception) {
            Output(
                document = "",
                success = false,
                error = "Failed to convert HTML: ${e.message}",
            )
        }
    }

    private companion object {
        val documentJson = Json {
            serializersModule = DocumentSerializers
            encodeDefaults = true
        }
    }
}
