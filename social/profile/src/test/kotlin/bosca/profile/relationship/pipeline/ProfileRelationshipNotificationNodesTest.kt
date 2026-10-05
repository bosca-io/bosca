package bosca.profile.relationship.pipeline

import bosca.communications.model.Message
import bosca.communications.model.MessageChannel
import bosca.communications.model.NotificationTypeKeys
import bosca.communications.service.MessageOutboxService
import bosca.profile.configuration.SocialNotificationConfiguration
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.events.ProfileRelationshipRequestApproved
import bosca.profile.relationship.events.ProfileRelationshipRequested
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.relationship.service.ProfileRelationshipRequestService
import bosca.profile.relationship.service.ProfileRelationshipService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRelationshipNotificationNodesTest {

    private val profileService = mockk<ProfileService>()
    private val requestService = mockk<ProfileRelationshipRequestService>()
    private val relationshipService = mockk<ProfileRelationshipService>()
    private val messageOutboxService = mockk<MessageOutboxService>()
    private val messages = mutableListOf<Message>()
    private val sourceIds = mutableListOf<UUID>()

    @BeforeTest
    fun setUp() {
        messages.clear()
        sourceIds.clear()
        coEvery { messageOutboxService.enqueueOnce(capture(sourceIds), capture(messages)) } returns Unit
    }

    @Test
    fun `relationship request notifies the target through configured channels`() = runBlocking {
        val requesterId = UUID.random()
        val targetId = UUID.random()
        val requestId = UUID.random()
        coEvery { requestService.getById(requestId) } returns request(requestId, requesterId, targetId)
        coEvery { profileService.getById(requesterId) } returns profile(requesterId, "Maya")
        coEvery { profileService.getById(targetId) } returns profile(targetId, "Jordan")
        val event = ProfileRelationshipRequested(requestId, requesterId, targetId, "friend")
        val node = ProfileRelationshipRequestedNotificationNode("send")

        node.deliver(event, requestService, profileService, configuration(), messageOutboxService)

        val message = messages.single()
        assertEquals(listOf(MessageChannel.EMAIL, MessageChannel.PUSH), message.channels)
        assertEquals(listOf(targetId), message.recipients)
        assertEquals(NotificationTypeKeys.SOCIAL_ACTIVITY, message.type)
        assertEquals("relationship-request", message.bmlTemplate?.templateKey)
        assertEquals("", message.subject, "the BML message unit owns localized subject copy")
        assertEquals(emptyList(), message.content, "the BML message unit owns localized body copy")
        assertNull(message.pushOptions?.defaultAction?.label, "the localized BML push region owns action copy")
        assertEquals("view-request", message.pushOptions?.defaultAction?.id)
        val actionUrl = "https://profiles.example/relationships?requestId=$requestId"
        assertEquals(actionUrl, message.pushOptions?.defaultAction?.url)
        assertEquals(
            actionUrl,
            message.bmlTemplate?.payload?.jsonObject?.get("actionUrl")?.jsonPrimitive?.content,
        )
        assertEquals("relationship-request-$requestId", message.pushOptions?.threadId)
        assertEquals("friend", message.pushOptions?.data?.get("relationship_type"))

        node.deliver(event, requestService, profileService, configuration(), messageOutboxService)
        assertEquals(sourceIds.first(), sourceIds.last(), "pipeline retries must reuse the outbox source ID")
    }

    @Test
    fun `relationship request supports push without email`() = runBlocking {
        val requesterId = UUID.random()
        val targetId = UUID.random()
        val requestId = UUID.random()
        coEvery { requestService.getById(requestId) } returns request(requestId, requesterId, targetId)
        coEvery { profileService.getById(requesterId) } returns profile(requesterId, "Maya")
        coEvery { profileService.getById(targetId) } returns profile(targetId, "Jordan")

        ProfileRelationshipRequestedNotificationNode("send", email = false).deliver(
            ProfileRelationshipRequested(requestId, requesterId, targetId, "friend"),
            requestService,
            profileService,
            configuration(),
            messageOutboxService,
        )

        assertEquals(listOf(MessageChannel.PUSH), messages.single().channels)
    }

    @Test
    fun `relationship request skips delivery after it is no longer pending`() = runBlocking {
        val requesterId = UUID.random()
        val targetId = UUID.random()
        val requestId = UUID.random()
        coEvery { requestService.getById(requestId) } returns request(
            requestId,
            requesterId,
            targetId,
            ProfileRelationshipRequestStatus.CANCELLED,
        )

        ProfileRelationshipRequestedNotificationNode("send").deliver(
            ProfileRelationshipRequested(requestId, requesterId, targetId, "friend"),
            requestService,
            profileService,
            configuration(),
            messageOutboxService,
        )

        assertTrue(messages.isEmpty())
    }

    @Test
    fun `relationship request skips a soft-deleted recipient`() = runBlocking {
        val requesterId = UUID.random()
        val targetId = UUID.random()
        val requestId = UUID.random()
        coEvery { requestService.getById(requestId) } returns request(requestId, requesterId, targetId)
        coEvery { profileService.getById(requesterId) } returns profile(requesterId, "Maya")
        coEvery { profileService.getById(targetId) } returns
            profile(targetId, "Jordan", deletedAt = OffsetDateTime.now())

        ProfileRelationshipRequestedNotificationNode("send").deliver(
            ProfileRelationshipRequested(requestId, requesterId, targetId, "friend"),
            requestService,
            profileService,
            configuration(),
            messageOutboxService,
        )

        assertTrue(messages.isEmpty())
    }

    @Test
    fun `relationship added notifies the originating profile`() = runBlocking {
        val recipientId = UUID.random()
        val relatedId = UUID.random()
        val requestId = UUID.random()
        coEvery { relationshipService.getRelationship(recipientId, relatedId, "friend") } returns
            ProfileRelationship(recipientId, relatedId, "friend")
        coEvery { profileService.getById(recipientId) } returns profile(recipientId, "Maya")
        coEvery { profileService.getById(relatedId) } returns profile(relatedId, "Jordan")

        ProfileRelationshipAddedNotificationNode("send").deliver(
            ProfileRelationshipRequestApproved(requestId, recipientId, relatedId, "friend"),
            relationshipService,
            profileService,
            configuration(),
            messageOutboxService,
        )

        val message = messages.single()
        assertEquals(listOf(MessageChannel.EMAIL, MessageChannel.PUSH), message.channels)
        assertEquals(listOf(recipientId), message.recipients)
        assertEquals("relationship-added", message.bmlTemplate?.templateKey)
        assertEquals("", message.subject, "the BML message unit owns localized subject copy")
        assertEquals(emptyList(), message.content, "the BML message unit owns localized body copy")
        assertNull(message.pushOptions?.defaultAction?.label, "the localized BML push region owns action copy")
        assertEquals("view-profile", message.pushOptions?.defaultAction?.id)
        val actionUrl = "https://app.example/audience/profiles/$relatedId"
        assertEquals(actionUrl, message.pushOptions?.defaultAction?.url)
        assertEquals(
            actionUrl,
            message.bmlTemplate?.payload?.jsonObject?.get("actionUrl")?.jsonPrimitive?.content,
        )
        assertEquals("relationship-$relatedId", message.pushOptions?.threadId)
        assertEquals("friend", message.pushOptions?.data?.get("relationship_type"))
    }

    @Test
    fun `relationship added skips delivery when relationship no longer exists`() = runBlocking {
        val recipientId = UUID.random()
        val relatedId = UUID.random()
        val requestId = UUID.random()
        coEvery { relationshipService.getRelationship(recipientId, relatedId, "friend") } returns null

        ProfileRelationshipAddedNotificationNode("send").deliver(
            ProfileRelationshipRequestApproved(requestId, recipientId, relatedId, "friend"),
            relationshipService,
            profileService,
            configuration(),
            messageOutboxService,
        )

        assertTrue(messages.isEmpty())
    }

    @Test
    fun `relationship added skips a soft-deleted recipient`() = runBlocking {
        val recipientId = UUID.random()
        val relatedId = UUID.random()
        val requestId = UUID.random()
        coEvery { relationshipService.getRelationship(recipientId, relatedId, "friend") } returns
            ProfileRelationship(recipientId, relatedId, "friend")
        coEvery { profileService.getById(recipientId) } returns
            profile(recipientId, "Maya", deletedAt = OffsetDateTime.now())
        coEvery { profileService.getById(relatedId) } returns profile(relatedId, "Jordan")

        ProfileRelationshipAddedNotificationNode("send").deliver(
            ProfileRelationshipRequestApproved(requestId, recipientId, relatedId, "friend"),
            relationshipService,
            profileService,
            configuration(),
            messageOutboxService,
        )

        assertTrue(messages.isEmpty())
    }

    private fun profile(id: UUID, name: String, deletedAt: OffsetDateTime? = null) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = name,
        visibility = ProfileVisibility.PUBLIC,
        deletedAt = deletedAt,
    )

    private fun request(
        id: UUID,
        requesterId: UUID,
        targetId: UUID,
        status: ProfileRelationshipRequestStatus = ProfileRelationshipRequestStatus.PENDING,
    ) = ProfileRelationshipRequest(
        id = id,
        requesterProfileId = requesterId,
        targetProfileId = targetId,
        type = "friend",
        status = status,
        version = 0,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private fun configuration() = SocialNotificationConfiguration(
        applicationUrl = "https://app.example",
        profileApplicationUrl = "https://profiles.example",
    )
}
