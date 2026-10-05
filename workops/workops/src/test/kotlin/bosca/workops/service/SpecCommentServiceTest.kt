@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.comments.model.CommentStatus
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecComment
import bosca.workops.model.spec.SpecCommentInput
import bosca.workops.model.spec.SpecCommented
import bosca.workops.model.spec.dispatch
import bosca.workops.repository.SpecCommentRepository
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.SpecRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SpecCommentServiceTest {

    private val comments = mockk<SpecCommentRepository>()
    private val specs = mockk<SpecRepository>()
    private val history = mockk<SpecHistoryRepository>(relaxed = true)
    private val service = SpecCommentServiceImpl(comments, specs, history, Json)
    private val specId = UUID.random()
    private val principalId = UUID.random()
    private val profileId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        mockkStatic("bosca.workops.model.spec.SpecCommentedExtKt")
        coEvery { any<SpecCommented>().dispatch() } just Runs
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.workops.model.spec.SpecCommentedExtKt")
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `add validates hierarchy persists authored fields audits and dispatches`() = runTest {
        val parent = comment(7)
        val saved = comment(42)
        val attributes = JsonObject(emptyMap())
        coEvery { specs.getActiveById(specId) } returns spec()
        coEvery { comments.getById(7) } returns parent
        coEvery {
            comments.add(
                7,
                specId,
                profileId,
                principalId,
                ProfileVisibility.PUBLIC,
                "Reply",
                attributes,
                attributes,
            )
        } returns 42
        coEvery { comments.getById(42) } returns saved

        val result = service.add(
            specId,
            SpecCommentInput(7, ProfileVisibility.PUBLIC, "Reply", attributes, attributes),
            principalId,
            profileId,
            principalId,
        )

        assertSame(saved, result)
        coVerify(exactly = 1) { any<SpecCommented>().dispatch() }
        verifyHistory("comment", "added:42", profileId)

        assertFailsWith<WorkOpsValidationException> {
            service.add(specId, SpecCommentInput(content = " \n"), principalId, profileId, null)
        }
        coEvery { specs.getActiveById(specId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.add(specId, SpecCommentInput(content = "Comment"), principalId, profileId, null)
        }
        coEvery { specs.getActiveById(specId) } returns spec()
        coEvery { comments.getById(8) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.add(specId, SpecCommentInput(8, content = "Reply"), principalId, profileId, null)
        }
        coEvery { comments.getById(8) } returns comment(8).copy(specId = UUID.random())
        assertFailsWith<WorkOpsValidationException> {
            service.add(specId, SpecCommentInput(8, content = "Reply"), principalId, profileId, null)
        }
        coEvery { comments.add(null, specId, profileId, null, ProfileVisibility.USER, "Gone", null, null) } returns 99
        coEvery { comments.getById(99) } returns null
        assertFailsWith<IllegalStateException> {
            service.add(specId, SpecCommentInput(content = "Gone"), principalId, profileId, null)
        }
    }

    @Test
    fun `reads likes and unlikes preserve visibility and idempotent reaction behavior`() = runTest {
        val expected = comment(1)
        coEvery { comments.getManager(specId, 1) } returns expected
        coEvery { comments.getForProfile(specId, 1, profileId) } returns expected
        coEvery { comments.getPublic(specId, 1) } returns expected
        coEvery { comments.listManager(specId, 2, 3) } returns listOf(expected)
        coEvery { comments.listForProfile(specId, profileId, 2, 3) } returns listOf(expected)
        coEvery { comments.listPublic(specId, 2, 3) } returns listOf(expected)
        coEvery { comments.countManager(specId) } returns 1
        assertSame(expected, service.getManager(specId, 1))
        assertSame(expected, service.getForProfile(specId, 1, profileId))
        assertSame(expected, service.getPublic(specId, 1))
        assertEquals(listOf(expected), service.listManager(specId, 2, 3))
        assertEquals(listOf(expected), service.listForProfile(specId, profileId, 2, 3))
        assertEquals(listOf(expected), service.listPublic(specId, 2, 3))
        assertEquals(1, service.countManager(specId))

        coEvery { comments.addLikeRow(1, profileId) } just Runs
        coEvery { comments.incrementLikes(specId, 1) } returns 2
        service.like(specId, 1, profileId)
        coEvery { comments.deleteLikeRow(1, profileId) } returns null
        service.unlike(specId, 1, profileId)
        coVerify(exactly = 0) { comments.decrementLikes(specId, 1) }
        coEvery { comments.deleteLikeRow(1, profileId) } returns 1
        coEvery { comments.decrementLikes(specId, 1) } returns 1
        service.unlike(specId, 1, profileId)
        coVerify(exactly = 1) { comments.decrementLikes(specId, 1) }
    }

    @Test
    fun `moderation and both deletion paths write their audit identity`() = runTest {
        coEvery { comments.setStatus(specId, 1, CommentStatus.APPROVED) } just Runs
        coEvery { comments.softDelete(specId, 2) } just Runs
        coEvery { comments.softDeleteByProfile(specId, 3, profileId) } just Runs

        service.setStatus(specId, 1, CommentStatus.APPROVED, principalId, null)
        service.delete(specId, 2, principalId, profileId)
        service.deleteByAuthor(specId, 3, profileId, principalId)

        verifyHistory("comment_status", "1:APPROVED", null)
        verifyHistory("comment", "deleted:2", profileId)
        verifyHistory("comment", "deleted_by_author:3", profileId)
    }

    private fun spec() = Spec(
        id = specId,
        key = "SPEC-1",
        metadataId = UUID.random(),
        statusId = UUID.random(),
        workflowId = UUID.random(),
        ownerProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun comment(id: Long) = SpecComment(
        id = id,
        specId = specId,
        profileId = profileId,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        status = CommentStatus.PENDING,
        content = "Comment",
    )

    private suspend fun verifyHistory(fieldKey: String, value: String, changedByProfileId: UUID?) {
        coVerify(atLeast = 1) {
            history.add(
                specId,
                any(),
                principalId,
                changedByProfileId,
                match {
                    val change = (it as JsonArray).single() as JsonObject
                    change["fieldKey"]?.jsonPrimitive?.content == fieldKey &&
                        change["toValue"]?.jsonPrimitive?.content == value
                },
            )
        }
    }
}
