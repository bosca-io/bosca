package bosca.content.metadata.model

import bosca.documents.BibleNode
import bosca.documents.ContainerNode
import bosca.documents.Content
import bosca.documents.Document
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the typed Content boundary used by GraphQL document saves and canonical storage. */
class DocumentEditorContentTest {
    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
        }
    }

    @Test
    fun `Studio reference attributes survive document input storage and attribute transformations`() {
        val input = """{"title":"Title","content":{"document":{"type":"doc","attrs":{"class":"article"},"content":[{"type":"container","attrs":{"name":"main","version":4,"references":["GEN.1"]},"content":[{"type":"paragraph"}]},{"type":"bible","attrs":{"usfm":["GEN.1.1"],"metadataIds":["bible-id"],"metadataId":"content-bible","references":["JHN.3.16"]}}]}}}"""
        val decoded = json.decodeFromString(DocumentInput.serializer(), input)
        val serialized = json.encodeToJsonElement(DocumentInput.serializer(), decoded).jsonObject.getValue("content")
        val stored = json.decodeFromJsonElement(Content.serializer(), serialized).document as Document
        assertEquals("article", stored.attributes.classes)
        val container = (stored.content[0] as ContainerNode).attributes
        assertEquals(4, container.version)
        assertEquals(4, container.withClasses("highlight").version)
        assertEquals(4, container.withReferences(null, emptyList()).version)
        val bible = (stored.content[1] as BibleNode).attributes
        assertEquals(listOf("GEN.1.1"), bible.usfm)
        assertEquals(listOf("bible-id"), bible.metadataIds)
        assertEquals(listOf("JHN.3.16"), bible.references)
        val styled = bible.withClasses("reference")
        assertEquals(bible.usfm, styled.usfm)
        assertEquals(bible.metadataIds, styled.metadataIds)
        assertEquals(bible.metadataId, styled.metadataId)
    }
}
