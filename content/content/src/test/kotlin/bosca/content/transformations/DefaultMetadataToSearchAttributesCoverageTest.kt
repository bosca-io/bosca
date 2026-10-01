package bosca.content.transformations

import bosca.content.metadata.model.Data
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers DefaultMetadataToSearchAttributes.toAttributes across every branch:
 *   - attributes null / JsonNull / non-JsonObject (JsonArray) / JsonObject
 *   - presence/absence of the "episode" and "season" keys (with quote/whitespace stripping)
 *   - document/guide/data each present vs. absent
 */
class DefaultMetadataToSearchAttributesCoverageTest {

    private val documentService = mockk<DocumentService>()
    private val guideService = mockk<GuideService>()
    private val dataService = mockk<DataService>()
    private val metadataService = mockk<MetadataService>()

    private val transformer = DefaultMetadataToSearchAttributes(
        documentService = documentService,
        guideService = guideService,
        dataService = dataService,
    )

    private val metadataId = UUID.random()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
    }

    private fun metadata(attributes: JsonElement? = null) = Metadata(
        id = metadataId,
        version = 3,
        name = "Test Metadata",
        type = MetadataType.STANDARD,
        languageTag = "en",
        contentType = "text/plain",
        contentLength = 100,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        public = true,
        workflowStateId = "published",
        attributes = attributes,
    )

    /** Default: nothing attached (all three services return null). */
    private fun stubNoAttachments() {
        coEvery { documentService.getDocument(metadataId, 3) } returns null
        coEvery { guideService.getGuide(metadataId, 3) } returns null
        coEvery { dataService.getData(metadataId, 3) } returns null
    }

    @Test
    fun `null attributes yields only has-flags, all false`() = runTest {
        stubNoAttachments()

        val result = transformer.toAttributes(metadataService, metadata(attributes = null))

        // No original attributes carried over.
        assertNull(result["episode"])
        assertNull(result["season"])
        assertFalse((result["hasDocument"] as JsonPrimitive).boolean)
        assertFalse((result["hasGuide"] as JsonPrimitive).boolean)
        assertFalse((result["hasData"] as JsonPrimitive).boolean)
        // exactly the three flags.
        assertEquals(setOf("hasDocument", "hasGuide", "hasData"), result.keys)
    }

    @Test
    fun `JsonNull attributes is treated as empty`() = runTest {
        stubNoAttachments()

        val result = transformer.toAttributes(metadataService, metadata(attributes = JsonNull))

        assertNull(result["episode"])
        assertNull(result["season"])
        assertEquals(setOf("hasDocument", "hasGuide", "hasData"), result.keys)
    }

    @Test
    fun `non-object attributes (JsonArray) is treated as empty`() = runTest {
        stubNoAttachments()

        val nonObject = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        val result = transformer.toAttributes(metadataService, metadata(attributes = nonObject))

        assertNull(result["episode"])
        assertNull(result["season"])
        assertEquals(setOf("hasDocument", "hasGuide", "hasData"), result.keys)
    }

    @Test
    fun `object attributes without episode or season are carried through untouched`() = runTest {
        stubNoAttachments()

        val attrs = JsonObject(mapOf("title" to JsonPrimitive("Hello")))
        val result = transformer.toAttributes(metadataService, metadata(attributes = attrs))

        assertEquals("Hello", (result["title"] as JsonPrimitive).content)
        // episode/season absent because containsKey was false.
        assertNull(result["episode"])
        assertNull(result["season"])
    }

    @Test
    fun `episode and season are stripped of quotes and trimmed`() = runTest {
        stubNoAttachments()

        val attrs = JsonObject(
            mapOf(
                "episode" to JsonPrimitive("  \"12\"  "),
                "season" to JsonPrimitive("\"2\""),
            ),
        )
        val result = transformer.toAttributes(metadataService, metadata(attributes = attrs))

        assertEquals("12", result["episode"]?.jsonPrimitive?.content)
        assertEquals("2", result["season"]?.jsonPrimitive?.content)
    }

    @Test
    fun `only episode present is normalized and season stays absent`() = runTest {
        stubNoAttachments()

        val attrs = JsonObject(mapOf("episode" to JsonPrimitive("\" 7 \"")))
        val result = transformer.toAttributes(metadataService, metadata(attributes = attrs))

        assertEquals("7", result["episode"]?.jsonPrimitive?.content)
        assertNull(result["season"])
    }

    @Test
    fun `only season present is normalized and episode stays absent`() = runTest {
        stubNoAttachments()

        val attrs = JsonObject(mapOf("season" to JsonPrimitive("  3  ")))
        val result = transformer.toAttributes(metadataService, metadata(attributes = attrs))

        assertEquals("3", result["season"]?.jsonPrimitive?.content)
        assertNull(result["episode"])
    }

    @Test
    fun `has-flags are true when document, guide, and data all present`() = runTest {
        coEvery { documentService.getDocument(metadataId, 3) } returns mockk<Document>()
        coEvery { guideService.getGuide(metadataId, 3) } returns mockk<Guide>()
        coEvery { dataService.getData(metadataId, 3) } returns mockk<Data>()

        val result = transformer.toAttributes(metadataService, metadata(attributes = null))

        assertTrue((result["hasDocument"] as JsonPrimitive).boolean)
        assertTrue((result["hasGuide"] as JsonPrimitive).boolean)
        assertTrue((result["hasData"] as JsonPrimitive).boolean)
    }

    @Test
    fun `has-flags reflect a mixed presence combination`() = runTest {
        coEvery { documentService.getDocument(metadataId, 3) } returns mockk<Document>()
        coEvery { guideService.getGuide(metadataId, 3) } returns null
        coEvery { dataService.getData(metadataId, 3) } returns mockk<Data>()

        val result = transformer.toAttributes(metadataService, metadata(attributes = null))

        assertTrue((result["hasDocument"] as JsonPrimitive).boolean)
        assertFalse((result["hasGuide"] as JsonPrimitive).boolean)
        assertTrue((result["hasData"] as JsonPrimitive).boolean)
    }
}
