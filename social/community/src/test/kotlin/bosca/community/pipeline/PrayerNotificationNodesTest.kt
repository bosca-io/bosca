package bosca.community.pipeline

import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.service.MessageOutboxService
import bosca.community.events.PrayerCommentAddedEvent
import bosca.community.events.PrayerReactionAddedEvent
import bosca.community.events.PrayerReactionType
import bosca.community.model.Prayer
import bosca.community.model.PrayerComment
import bosca.community.model.PrayerStatus
import bosca.community.service.PrayerService
import bosca.profile.configuration.SocialNotificationConfiguration
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrayerNotificationNodesTest {

    @Test
    fun `reaction queues idempotent push for the active prayer owner`() = runTest {
        val event = reactionEvent(PrayerReactionType.LIKED)
        val ownerId = UUID.random()
        val prayerService = mockk<PrayerService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val capturedId = slot<UUID>()
        val captured = slot<Message>()
        coEvery { prayerService.getRequest(event.prayerId) } returns prayer(event.prayerId, ownerId)
        coEvery { prayerService.hasLiked(event.prayerId, event.reactorId) } returns true
        coEvery { profileService.getAllByIds(listOf(ownerId)) } returns listOf(profile(ownerId, "Owner"))
        coEvery { profileService.getById(event.reactorId) } returns profile(event.reactorId, "Avery")
        coEvery { outbox.enqueueOnce(capture(capturedId), capture(captured)) } returns Unit
        val node = PrayerReactionNotificationNode("send")

        node.deliver(event, prayerService, profileService, configuration(), outbox)

        val message = captured.captured
        assertEquals(node.notificationId(event), capturedId.captured)
        assertEquals(listOf(MessageChannel.PUSH), message.channels)
        assertEquals(listOf(ownerId), message.recipients)
        assertEquals(event.reactorId, message.sender)
        assertEquals(NotificationTypeKeys.SOCIAL_ACTIVITY, message.type)
        assertEquals("bosca-messages", message.bmlTemplate?.project)
        assertEquals("prayer-reaction", message.bmlTemplate?.templateKey)
        assertEquals("Avery", message.bmlTemplate?.payload?.jsonObject?.get("actorName")?.jsonPrimitive?.content)
        assertEquals("LIKED", message.bmlTemplate?.payload?.jsonObject?.get("reaction")?.jsonPrimitive?.content)
        assertEquals("PRAYER_REACTION", message.pushOptions?.category)
        assertEquals("prayer_reaction", message.pushOptions?.data?.get("event"))
        assertEquals("liked", message.pushOptions?.data?.get("reaction"))
        assertEquals(
            "https://app.example.com/prayers/${event.prayerId}",
            message.pushOptions?.defaultAction?.url,
        )
    }

    @Test
    fun `prayed reaction verifies the current prayed state`() = runTest {
        val event = reactionEvent(PrayerReactionType.PRAYED)
        val ownerId = UUID.random()
        val prayerService = mockk<PrayerService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        coEvery { prayerService.getRequest(event.prayerId) } returns prayer(event.prayerId, ownerId)
        coEvery { prayerService.hasPrayed(event.prayerId, event.reactorId) } returns true
        coEvery { profileService.getAllByIds(listOf(ownerId)) } returns listOf(profile(ownerId, "Owner"))
        coEvery { profileService.getById(event.reactorId) } returns profile(event.reactorId, "Avery")
        coEvery { outbox.enqueueOnce(any(), any()) } returns Unit

        PrayerReactionNotificationNode("send").deliver(
            event,
            prayerService,
            profileService,
            configuration(),
            outbox,
        )

        coVerify(exactly = 1) { prayerService.hasPrayed(event.prayerId, event.reactorId) }
        coVerify(exactly = 0) { prayerService.hasLiked(any(), any()) }
        coVerify(exactly = 1) { outbox.enqueueOnce(any(), any()) }
    }

    @Test
    fun `reaction skips self activity disabled push and removed reactions`() = runTest {
        val ownerId = UUID.random()
        val base = reactionEvent()
        val prayerService = mockk<PrayerService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        coEvery { prayerService.getRequest(base.prayerId) } returns prayer(base.prayerId, ownerId)
        coEvery { prayerService.hasLiked(base.prayerId, base.reactorId) } returns false

        PrayerReactionNotificationNode("send", push = false).deliver(
            base,
            prayerService,
            profileService,
            configuration(),
            outbox,
        )
        PrayerReactionNotificationNode("send").deliver(
            base.copy(reactorId = ownerId),
            prayerService,
            profileService,
            configuration(),
            outbox,
        )
        PrayerReactionNotificationNode("send").deliver(
            base,
            prayerService,
            profileService,
            configuration(),
            outbox,
        )

        coVerify(exactly = 0) { outbox.enqueueOnce(any(), any()) }
    }

    @Test
    fun `top level comment notifies the prayer owner`() = runTest {
        val event = commentEvent()
        val ownerId = UUID.random()
        val prayerService = mockk<PrayerService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val capturedId = slot<UUID>()
        val captured = slot<Message>()
        coEvery { prayerService.getRequest(event.prayerId) } returns prayer(event.prayerId, ownerId)
        coEvery { prayerService.getComment(event.commentId) } returns comment(event)
        coEvery { profileService.getById(event.commenterId) } returns profile(event.commenterId, "Avery")
        coEvery { profileService.getAllByIds(listOf(ownerId)) } returns listOf(profile(ownerId, "Owner"))
        coEvery { outbox.enqueueOnce(capture(capturedId), capture(captured)) } returns Unit
        val node = PrayerCommentNotificationNode("send")

        node.deliver(event, prayerService, profileService, configuration(), outbox)

        val message = captured.captured
        assertEquals(node.notificationId(event, reply = false), capturedId.captured)
        assertEquals(listOf(ownerId), message.recipients)
        assertEquals(listOf(MessageChannel.PUSH), message.channels)
        assertEquals("prayer-comment", message.bmlTemplate?.templateKey)
        assertFalse(message.bmlTemplate?.payload?.jsonObject?.get("reply")?.jsonPrimitive?.content?.toBoolean() ?: true)
        assertEquals("PRAYER_COMMENT", message.pushOptions?.category)
        assertEquals(event.commentId.toString(), message.pushOptions?.data?.get("comment_id"))
        assertEquals("false", message.pushOptions?.data?.get("reply"))
        assertEquals(
            "https://app.example.com/prayers/${event.prayerId}?commentId=${event.commentId}",
            message.pushOptions?.defaultAction?.url,
        )
    }

    @Test
    fun `reply notifies owner and parent comment author with recipient specific copy`() = runTest {
        val parentId = 41L
        val event = commentEvent(parentId = parentId)
        val ownerId = UUID.random()
        val parentAuthorId = UUID.random()
        val prayerService = mockk<PrayerService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val sourceIds = mutableListOf<UUID>()
        val messages = mutableListOf<Message>()
        coEvery { prayerService.getRequest(event.prayerId) } returns prayer(event.prayerId, ownerId)
        coEvery { prayerService.getComment(event.commentId) } returns comment(event)
        coEvery { prayerService.getComment(parentId) } returns comment(
            event.copy(commentId = parentId, commenterId = parentAuthorId, parentId = null),
        )
        coEvery { profileService.getById(event.commenterId) } returns profile(event.commenterId, "Avery")
        coEvery { profileService.getAllByIds(listOf(ownerId, parentAuthorId)) } returns listOf(
            profile(ownerId, "Owner"),
            profile(parentAuthorId, "Parent Author"),
        )
        coEvery { outbox.enqueueOnce(capture(sourceIds), capture(messages)) } returns Unit
        val node = PrayerCommentNotificationNode("send")

        node.deliver(event, prayerService, profileService, configuration(), outbox)

        assertEquals(2, messages.size)
        val byRecipient = messages.associateBy { it.recipients.single() }
        assertFalse(byRecipient.getValue(ownerId).bmlTemplate?.payload?.jsonObject?.get("reply")?.jsonPrimitive?.content?.toBoolean() ?: true)
        assertTrue(byRecipient.getValue(parentAuthorId).bmlTemplate?.payload?.jsonObject?.get("reply")?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals("false", byRecipient.getValue(ownerId).pushOptions?.data?.get("reply"))
        assertEquals("true", byRecipient.getValue(parentAuthorId).pushOptions?.data?.get("reply"))
        assertNotEquals(sourceIds[0], sourceIds[1])
    }

    @Test
    fun `reply to prayer owner produces one reply notification`() = runTest {
        val parentId = 41L
        val event = commentEvent(parentId = parentId)
        val ownerId = UUID.random()
        val prayerService = mockk<PrayerService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        val messages = mutableListOf<Message>()
        coEvery { prayerService.getRequest(event.prayerId) } returns prayer(event.prayerId, ownerId)
        coEvery { prayerService.getComment(event.commentId) } returns comment(event)
        coEvery { prayerService.getComment(parentId) } returns comment(
            event.copy(commentId = parentId, commenterId = ownerId, parentId = null),
        )
        coEvery { profileService.getById(event.commenterId) } returns profile(event.commenterId, "Avery")
        coEvery { profileService.getAllByIds(listOf(ownerId)) } returns listOf(profile(ownerId, "Owner"))
        coEvery { outbox.enqueueOnce(any(), capture(messages)) } returns Unit

        PrayerCommentNotificationNode("send").deliver(
            event,
            prayerService,
            profileService,
            configuration(),
            outbox,
        )

        assertEquals(1, messages.size)
        assertTrue(messages.single().bmlTemplate?.payload?.jsonObject?.get("reply")?.jsonPrimitive?.content?.toBoolean() == true)
    }

    @Test
    fun `comment skips self comments disabled push and stale comment state`() = runTest {
        val event = commentEvent()
        val prayerService = mockk<PrayerService>()
        val profileService = mockk<ProfileService>()
        val outbox = mockk<MessageOutboxService>()
        coEvery { prayerService.getRequest(event.prayerId) } returns prayer(event.prayerId, event.commenterId)
        coEvery { prayerService.getComment(event.commentId) } returns comment(event)
        coEvery { profileService.getById(event.commenterId) } returns profile(event.commenterId, "Avery")

        PrayerCommentNotificationNode("send", push = false).deliver(
            event,
            prayerService,
            profileService,
            configuration(),
            outbox,
        )
        PrayerCommentNotificationNode("send").deliver(
            event,
            prayerService,
            profileService,
            configuration(),
            outbox,
        )
        coEvery { prayerService.getRequest(event.prayerId) } returns prayer(event.prayerId, UUID.random())
        coEvery { prayerService.getComment(event.commentId) } returns comment(event).copy(deleted = true)
        PrayerCommentNotificationNode("send").deliver(
            event,
            prayerService,
            profileService,
            configuration(),
            outbox,
        )

        coVerify(exactly = 0) { outbox.enqueueOnce(any(), any()) }
    }

    private fun reactionEvent(reaction: PrayerReactionType = PrayerReactionType.LIKED) = PrayerReactionAddedEvent(
        activityId = UUID.random(),
        prayerId = UUID.random(),
        reactorId = UUID.random(),
        reaction = reaction,
    )

    private fun commentEvent(parentId: Long? = null) = PrayerCommentAddedEvent(
        commentId = 42L,
        prayerId = UUID.random(),
        commenterId = UUID.random(),
        parentId = parentId,
    )

    private fun prayer(id: UUID, ownerId: UUID) = Prayer(
        id = id,
        profileId = ownerId,
        title = "A prayer",
        content = JsonPrimitive("content"),
        status = PrayerStatus.ACTIVE,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
        lastActivityAt = OffsetDateTime.now(),
        attributes = null,
    )

    private fun comment(event: PrayerCommentAddedEvent) = PrayerComment(
        id = event.commentId,
        prayerId = event.prayerId,
        parentId = event.parentId,
        profileId = event.commenterId,
        content = "A comment",
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private fun profile(id: UUID, name: String) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.PUBLIC,
    )

    private fun configuration() = SocialNotificationConfiguration("https://app.example.com")
}
