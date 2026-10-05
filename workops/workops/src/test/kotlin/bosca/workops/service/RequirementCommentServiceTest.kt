package bosca.workops.service

import bosca.comments.model.CommentStatus
import bosca.profile.model.ProfileVisibility
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementComment
import bosca.workops.model.requirement.RequirementCommentInput
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.repository.RequirementCommentRepository
import bosca.workops.repository.RequirementHistoryRepository
import bosca.workops.repository.RequirementRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(bosca.di.annotation.InternalDI::class)
class RequirementCommentServiceTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        bosca.di.provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }
        bosca.di.provides<Json>(singleton = true) { Json }
    }

    private val commentRepository = mockk<RequirementCommentRepository>()
    private val requirementRepository = mockk<RequirementRepository>()
    private val historyRepository = mockk<RequirementHistoryRepository>()
    private val json = Json { ignoreUnknownKeys = true }

    private val service = RequirementCommentServiceImpl(
        commentRepository = commentRepository,
        requirementRepository = requirementRepository,
        requirementHistoryRepository = historyRepository,
        json = json,
    )

    private val requirementId = UUID.random()
    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val impersonatorId = UUID.random()

    private val requirement = Requirement(
        id = requirementId,
        key = "REQ-1",
        metadataId = UUID.random(),
        parentType = RequirementParent.SPEC,
        parentId = UUID.random(),
        statusId = UUID.random(),
        workflowId = UUID.random(),
        priorityId = UUID.random(),
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun comment(id: Long = 1, requirementId: UUID = this.requirementId) = RequirementComment(
        id = id,
        requirementId = requirementId,
        profileId = profileId,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        status = CommentStatus.PENDING,
        content = "comment",
    )

    @Test
    fun `add persists reply fields dispatches event and records history`() = runTest {
        val attributes = JsonObject(mapOf("client" to json.parseToJsonElement("\"web\"")))
        val systemAttributes = JsonObject(mapOf("source" to json.parseToJsonElement("\"test\"")))
        val saved = comment(id = 42)
        coEvery { requirementRepository.getActiveById(requirementId) } returns requirement
        coEvery { commentRepository.getById(7) } returns comment(id = 7)
        coEvery {
            commentRepository.add(
                parentId = 7,
                requirementId = requirementId,
                profileId = profileId,
                impersonatorId = impersonatorId,
                visibility = ProfileVisibility.FRIENDS,
                content = "reply",
                attributes = attributes,
                systemAttributes = systemAttributes,
            )
        } returns 42
        coEvery { commentRepository.getById(42) } returns saved
        coEvery { historyRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val result = service.add(
            requirementId = requirementId,
            input = RequirementCommentInput(
                parentId = 7,
                visibility = ProfileVisibility.FRIENDS,
                content = "reply",
                attributes = attributes,
                systemAttributes = systemAttributes,
            ),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
            impersonatorId = impersonatorId,
        )

        assertEquals(saved, result)
        verifyHistory("comment", "added:42", profileId)
    }

    @Test
    fun `add rejects blank content before repository access`() = runTest {
        assertFailsWith<WorkOpsValidationException> {
            service.add(requirementId, RequirementCommentInput(content = "  \n"), principalId, profileId)
        }

        coVerify(exactly = 0) { requirementRepository.getActiveById(any()) }
    }

    @Test
    fun `add rejects a missing requirement`() = runTest {
        coEvery { requirementRepository.getActiveById(requirementId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.add(requirementId, RequirementCommentInput(content = "comment"), principalId, profileId)
        }
    }

    @Test
    fun `add rejects a missing parent comment`() = runTest {
        coEvery { requirementRepository.getActiveById(requirementId) } returns requirement
        coEvery { commentRepository.getById(7) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.add(requirementId, RequirementCommentInput(parentId = 7, content = "reply"), principalId, profileId)
        }
    }

    @Test
    fun `add rejects a parent comment from another requirement`() = runTest {
        coEvery { requirementRepository.getActiveById(requirementId) } returns requirement
        coEvery { commentRepository.getById(7) } returns comment(id = 7, requirementId = UUID.random())

        assertFailsWith<WorkOpsValidationException> {
            service.add(requirementId, RequirementCommentInput(parentId = 7, content = "reply"), principalId, profileId)
        }
    }

    @Test
    fun `add fails when the inserted comment cannot be reloaded`() = runTest {
        coEvery { requirementRepository.getActiveById(requirementId) } returns requirement
        coEvery { commentRepository.add(any(), any(), any(), any(), any(), any(), any(), any()) } returns 42
        coEvery { commentRepository.getById(42) } returns null

        assertFailsWith<IllegalStateException> {
            service.add(requirementId, RequirementCommentInput(content = "comment"), principalId, profileId)
        }
    }

    @Test
    fun `read operations delegate all visibility variants`() = runTest {
        val expected = listOf(comment())
        coEvery { commentRepository.getManager(requirementId, 1) } returns expected.single()
        coEvery { commentRepository.getForProfile(requirementId, 1, profileId) } returns expected.single()
        coEvery { commentRepository.getPublic(requirementId, 1) } returns expected.single()
        coEvery { commentRepository.listManager(requirementId, 2, 3) } returns expected
        coEvery { commentRepository.listForProfile(requirementId, profileId, 2, 3) } returns expected
        coEvery { commentRepository.listPublic(requirementId, 2, 3) } returns expected
        coEvery { commentRepository.countManager(requirementId) } returns 4

        assertEquals(expected.single(), service.getManager(requirementId, 1))
        assertEquals(expected.single(), service.getForProfile(requirementId, 1, profileId))
        assertEquals(expected.single(), service.getPublic(requirementId, 1))
        assertEquals(expected, service.listManager(requirementId, 2, 3))
        assertEquals(expected, service.listForProfile(requirementId, profileId, 2, 3))
        assertEquals(expected, service.listPublic(requirementId, 2, 3))
        assertEquals(4, service.countManager(requirementId))
    }

    @Test
    fun `like creates the like row before incrementing the counter`() = runTest {
        coEvery { commentRepository.addLikeRow(1, profileId) } just Runs
        coEvery { commentRepository.incrementLikes(requirementId, 1) } returns 2

        service.like(requirementId, 1, profileId)

        coVerify(ordering = io.mockk.Ordering.SEQUENCE) {
            commentRepository.addLikeRow(1, profileId)
            commentRepository.incrementLikes(requirementId, 1)
        }
    }

    @Test
    fun `unlike decrements only when a like row was deleted`() = runTest {
        coEvery { commentRepository.deleteLikeRow(1, profileId) } returns 1
        coEvery { commentRepository.decrementLikes(requirementId, 1) } returns 0

        service.unlike(requirementId, 1, profileId)

        coVerify(exactly = 1) { commentRepository.decrementLikes(requirementId, 1) }
    }

    @Test
    fun `unlike leaves the counter when no like row existed`() = runTest {
        coEvery { commentRepository.deleteLikeRow(1, profileId) } returns null

        service.unlike(requirementId, 1, profileId)

        coVerify(exactly = 0) { commentRepository.decrementLikes(any(), any()) }
    }

    @Test
    fun `set status records moderation history without a profile`() = runTest {
        coEvery { commentRepository.setStatus(requirementId, 1, CommentStatus.APPROVED) } just Runs
        coEvery { historyRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        service.setStatus(requirementId, 1, CommentStatus.APPROVED, principalId, null)

        verifyHistory("comment_status", "1:APPROVED", null)
    }

    @Test
    fun `delete soft deletes and records history`() = runTest {
        coEvery { commentRepository.softDelete(requirementId, 1) } just Runs
        coEvery { historyRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        service.delete(requirementId, 1, principalId, profileId)

        coVerify(exactly = 1) { commentRepository.softDelete(requirementId, 1) }
        verifyHistory("comment", "deleted:1", profileId)
    }

    @Test
    fun `delete by author scopes deletion and attribution to the author`() = runTest {
        coEvery { commentRepository.softDeleteByProfile(requirementId, 1, profileId) } just Runs
        coEvery { historyRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        service.deleteByAuthor(requirementId, 1, profileId, principalId)

        coVerify(exactly = 1) { commentRepository.softDeleteByProfile(requirementId, 1, profileId) }
        verifyHistory("comment", "deleted_by_author:1", profileId)
    }

    private suspend fun verifyHistory(fieldKey: String, value: String, changedByProfileId: UUID?) {
        coVerify(exactly = 1) {
            historyRepository.add(
                requirementId = requirementId,
                changedAt = any(),
                changedByPrincipalId = principalId,
                changedByProfileId = changedByProfileId,
                changes = match {
                    val change = (it as JsonArray).single() as JsonObject
                    change["fieldKey"]?.jsonPrimitive?.content == fieldKey &&
                            change["toValue"]?.jsonPrimitive?.content == value
                },
            )
        }
    }
}
