package bosca.content.healthcheck

import bosca.content.metadata.model.ContentHealthCheckItem
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Unit coverage for [ContentHealthCheckController].
 *
 * Every `@Field` resolver first calls [GroupEvaluator.verifyHasEditorGroup] and then delegates to
 * a [ContentHealthCheckRepository] method. All collaborators are mocked. Coverage targets:
 *   - the editor-group guard on every field (success + denial),
 *   - the private `clampLimit` / `clampOffset` helpers, exercising both sides of their
 *     `?:` null-defaulting and their `coerceIn` / `coerceAtLeast` bounds (null, in-range,
 *     below-min, above-max, negative),
 *   - the [deletedItems] count field wrapping the repository count in a [ContentHealthCheckCount].
 */
class ContentHealthCheckControllerCoverageTest {

    private val repository = mockk<ContentHealthCheckRepository>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = ContentHealthCheckController(repository, groupEvaluator)

    private val authentication = mockk<AuthenticationContext>()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun allowEditor() {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } returns Unit
    }

    private fun item(): ContentHealthCheckItem = ContentHealthCheckItem(
        id = UUID.random(),
        name = "item",
        workflowState = "draft",
    )

    // ---- editor-group guard denials (both sides of every field's guard) ----

    @Test
    fun `publishedWithUnpublishedRelationships throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")
        assertFailsWith<IllegalStateException> {
            controller.publishedWithUnpublishedRelationships(authentication, 0, 10)
        }
        coVerify(exactly = 0) { repository.findPublishedWithUnpublishedRelationships(any(), any()) }
    }

    @Test
    fun `scheduledButNotPublished throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")
        assertFailsWith<IllegalStateException> {
            controller.scheduledButNotPublished(authentication, 0, 10)
        }
        coVerify(exactly = 0) { repository.findScheduledButNotPublished(any(), any()) }
    }

    @Test
    fun `pendingNotReady throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")
        assertFailsWith<IllegalStateException> {
            controller.pendingNotReady(authentication, 0, 10)
        }
        coVerify(exactly = 0) { repository.findPendingNotReady(any(), any()) }
    }

    @Test
    fun `guidesWithUnpublishedSteps throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")
        assertFailsWith<IllegalStateException> {
            controller.guidesWithUnpublishedSteps(authentication, 0, 10)
        }
        coVerify(exactly = 0) { repository.findGuidesWithUnpublishedSteps(any(), any()) }
    }

    @Test
    fun `publishedCollectionsWithUnpublishedMetadata throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")
        assertFailsWith<IllegalStateException> {
            controller.publishedCollectionsWithUnpublishedMetadata(authentication, 0, 10)
        }
        coVerify(exactly = 0) { repository.findPublishedCollectionsWithUnpublishedMetadata(any(), any()) }
    }

    @Test
    fun `failedJobItems throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")
        assertFailsWith<IllegalStateException> {
            controller.failedJobItems(authentication, 0, 10)
        }
        coVerify(exactly = 0) { repository.findFailedJobItems(any(), any()) }
    }

    @Test
    fun `deletedItems throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")
        assertFailsWith<IllegalStateException> {
            controller.deletedItems(authentication)
        }
        coVerify(exactly = 0) { repository.countDeletedItems() }
    }

    @Test
    fun `missingContent throws when editor group check fails`() = runTest {
        every { groupEvaluator.verifyHasEditorGroup(authentication) } throws IllegalStateException("nope")
        assertFailsWith<IllegalStateException> {
            controller.missingContent(authentication, 0, 10)
        }
        coVerify(exactly = 0) { repository.findMissingContent(any(), any()) }
    }

    // ---- success delegation (each field returns the repository result verbatim) ----

    @Test
    fun `publishedWithUnpublishedRelationships returns repository result`() = runTest {
        allowEditor()
        val expected = listOf(item())
        coEvery { repository.findPublishedWithUnpublishedRelationships(0, 10) } returns expected
        val result = controller.publishedWithUnpublishedRelationships(authentication, 0, 10)
        assertSame(expected, result)
    }

    @Test
    fun `scheduledButNotPublished returns repository result`() = runTest {
        allowEditor()
        val expected = listOf(item())
        coEvery { repository.findScheduledButNotPublished(0, 10) } returns expected
        val result = controller.scheduledButNotPublished(authentication, 0, 10)
        assertSame(expected, result)
    }

    @Test
    fun `pendingNotReady returns repository result`() = runTest {
        allowEditor()
        val expected = listOf(item())
        coEvery { repository.findPendingNotReady(0, 10) } returns expected
        val result = controller.pendingNotReady(authentication, 0, 10)
        assertSame(expected, result)
    }

    @Test
    fun `guidesWithUnpublishedSteps returns repository result`() = runTest {
        allowEditor()
        val expected = listOf(item())
        coEvery { repository.findGuidesWithUnpublishedSteps(0, 10) } returns expected
        val result = controller.guidesWithUnpublishedSteps(authentication, 0, 10)
        assertSame(expected, result)
    }

    @Test
    fun `publishedCollectionsWithUnpublishedMetadata returns repository result`() = runTest {
        allowEditor()
        val expected = listOf(item())
        coEvery { repository.findPublishedCollectionsWithUnpublishedMetadata(0, 10) } returns expected
        val result = controller.publishedCollectionsWithUnpublishedMetadata(authentication, 0, 10)
        assertSame(expected, result)
    }

    @Test
    fun `failedJobItems returns repository result`() = runTest {
        allowEditor()
        val expected = listOf(item())
        coEvery { repository.findFailedJobItems(0, 10) } returns expected
        val result = controller.failedJobItems(authentication, 0, 10)
        assertSame(expected, result)
    }

    @Test
    fun `missingContent returns repository result`() = runTest {
        allowEditor()
        val expected = listOf(item())
        coEvery { repository.findMissingContent(0, 10) } returns expected
        val result = controller.missingContent(authentication, 0, 10)
        assertSame(expected, result)
    }

    @Test
    fun `deletedItems wraps repository count`() = runTest {
        allowEditor()
        coEvery { repository.countDeletedItems() } returns 7
        val result = controller.deletedItems(authentication)
        assertEquals(ContentHealthCheckCount(7), result)
        assertEquals(7, result.count)
    }

    // ---- clampOffset / clampLimit branches ----------------------------------
    //
    // Verified through the offset/limit args actually passed to the repository. Using
    // publishedWithUnpublishedRelationships as the vehicle; the helpers are shared by all fields.

    @Test
    fun `null offset and null limit fall back to defaults`() = runTest {
        allowEditor()
        val offsetSlot = slot<Int>()
        val limitSlot = slot<Int>()
        coEvery {
            repository.findPublishedWithUnpublishedRelationships(capture(offsetSlot), capture(limitSlot))
        } returns emptyList()

        controller.publishedWithUnpublishedRelationships(authentication, null, null)

        assertEquals(0, offsetSlot.captured)   // offset ?: 0
        assertEquals(100, limitSlot.captured)  // limit ?: DEFAULT_LIMIT
    }

    @Test
    fun `in-range offset and limit pass through unchanged`() = runTest {
        allowEditor()
        val offsetSlot = slot<Int>()
        val limitSlot = slot<Int>()
        coEvery {
            repository.findPublishedWithUnpublishedRelationships(capture(offsetSlot), capture(limitSlot))
        } returns emptyList()

        controller.publishedWithUnpublishedRelationships(authentication, 25, 50)

        assertEquals(25, offsetSlot.captured)
        assertEquals(50, limitSlot.captured)
    }

    @Test
    fun `negative offset clamps to zero and zero limit clamps to one`() = runTest {
        allowEditor()
        val offsetSlot = slot<Int>()
        val limitSlot = slot<Int>()
        coEvery {
            repository.findPublishedWithUnpublishedRelationships(capture(offsetSlot), capture(limitSlot))
        } returns emptyList()

        controller.publishedWithUnpublishedRelationships(authentication, -5, 0)

        assertEquals(0, offsetSlot.captured)   // coerceAtLeast(0) below-bound arm
        assertEquals(1, limitSlot.captured)    // coerceIn(1, MAX_LIMIT) below-min arm
    }

    @Test
    fun `limit above max clamps to max`() = runTest {
        allowEditor()
        val offsetSlot = slot<Int>()
        val limitSlot = slot<Int>()
        coEvery {
            repository.findPublishedWithUnpublishedRelationships(capture(offsetSlot), capture(limitSlot))
        } returns emptyList()

        controller.publishedWithUnpublishedRelationships(authentication, 3, 1000)

        assertEquals(3, offsetSlot.captured)
        assertEquals(500, limitSlot.captured) // coerceIn(1, MAX_LIMIT) above-max arm
    }
}
