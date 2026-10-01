package bosca.content.metadata.graphql

import bosca.content.collection.model.Collection
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataAIService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MetadataAIControllerCoverageTest {

    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val metadataAIService = mockk<MetadataAIService>()

    private val controller = MetadataAIController(metadataPermissionEvaluator, metadataAIService)
    private val authentication = mockk<AuthenticationContext>()

    private fun metadata() = Metadata(
        name = "Doc",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "published"
    )

    private fun ai() = MetadataAI(metadata())

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `MetadataAI exposes its metadata`() {
        val m = metadata()
        val ai = MetadataAI(m)

        assertEquals(m, ai.metadata)
    }

    // --- description ---

    @Test
    fun `description returns null when not allowed`() = runTest {
        val ai = ai()
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns false

        assertNull(controller.description(authentication, ai, null))
    }

    @Test
    fun `description returns service value when allowed with document`() = runTest {
        val ai = ai()
        val document = DocumentInput(title = "T", content = null)
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { metadataAIService.description(ai.metadata, document) } returns "a summary"

        assertEquals("a summary", controller.description(authentication, ai, document))
    }

    @Test
    fun `description returns service value when allowed with null document`() = runTest {
        val ai = ai()
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { metadataAIService.description(ai.metadata, null) } returns "stored summary"

        assertEquals("stored summary", controller.description(authentication, ai, null))
    }

    // --- topics ---

    @Test
    fun `topics returns empty list when not allowed`() = runTest {
        val ai = ai()
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns false

        assertTrue(controller.topics(authentication, ai, null).isEmpty())
    }

    @Test
    fun `topics returns service collections when allowed`() = runTest {
        val ai = ai()
        val document = DocumentInput(title = "T", content = null)
        val collections = listOf(
            Collection(name = "Faith", languageTag = "en", workflowStateId = "published")
        )
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { metadataAIService.topics(ai.metadata, document) } returns collections

        assertEquals(collections, controller.topics(authentication, ai, document))
    }

    @Test
    fun `topics returns service collections when allowed with null document`() = runTest {
        val ai = ai()
        val collections = listOf(
            Collection(name = "Hope", languageTag = "en", workflowStateId = "published")
        )
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { metadataAIService.topics(ai.metadata, null) } returns collections

        assertEquals(collections, controller.topics(authentication, ai, null))
    }

    // --- readingTimeInMinutes ---

    @Test
    fun `readingTimeInMinutes returns zero when not allowed`() = runTest {
        val ai = ai()
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns false

        assertEquals(0, controller.readingTimeInMinutes(authentication, ai, null))
    }

    @Test
    fun `readingTimeInMinutes returns service value when allowed`() = runTest {
        val ai = ai()
        val document = DocumentInput(title = "T", content = null)
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { metadataAIService.readingTimeInMinutes(ai.metadata, document) } returns 7

        assertEquals(7, controller.readingTimeInMinutes(authentication, ai, document))
    }

    @Test
    fun `readingTimeInMinutes returns service value when allowed with null document`() = runTest {
        val ai = ai()
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns true
        coEvery { metadataAIService.readingTimeInMinutes(ai.metadata, null) } returns 3

        assertEquals(3, controller.readingTimeInMinutes(authentication, ai, null))
    }

    // --- content ---

    @Test
    fun `content returns JsonNull when not allowed`() = runTest {
        val ai = ai()
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns false

        assertEquals(JsonNull, controller.content(authentication, ai, null, "html"))
    }

    @Test
    fun `content throws not implemented when allowed`() = runTest {
        val ai = ai()
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, ai.metadata, PermissionAction.EXECUTE)
        } returns true

        assertFailsWith<NotImplementedError> {
            controller.content(authentication, ai, null, "html")
        }
    }
}
