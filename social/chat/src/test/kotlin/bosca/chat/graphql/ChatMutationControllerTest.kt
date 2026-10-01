package bosca.chat.graphql

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatMessage
import bosca.chat.model.ChatObjectType
import bosca.chat.security.ChatChannelPermissionEvaluator
import bosca.chat.security.ChatObjectPermissionEvaluator
import bosca.chat.service.ChatService
import bosca.chat.service.ChatChannelInvitationService
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Focused tests for [ChatMutationController.objectChannel]. The mutation is the
 * UI's single entry-point for opening an object-scoped chat (a chat thread
 * pinned to a metadata item, collection, calendar event, etc.) so it has to
 *
 *  * verify the caller has authenticated messaging access,
 *  * upsert the channel via [ChatService.getOrCreateObjectChannel],
 *  * and idempotently add the caller's primary profile as a member so they
 *    appear in the roster the next time anyone opens the channel.
 */
class ChatMutationControllerCreateChannelTest {

    private val chatService = mockk<ChatService>(relaxed = true)
    private val chatChannelPermissionEvaluator = mockk<ChatChannelPermissionEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>()
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val invitationService = mockk<ChatChannelInvitationService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionService = mockk<CollectionService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val chatObjectPermissionEvaluator = mockk<ChatObjectPermissionEvaluator>(relaxed = true)

    private val controller = ChatMutationController(
        chatService = chatService,
        chatChannelPermissionEvaluator = chatChannelPermissionEvaluator,
        profileService = profileService,
        profilePermissionEvaluator = profilePermissionEvaluator,
        groupEvaluator = groupEvaluator,
        invitationService = invitationService,
        metadataService = metadataService,
        metadataPermissionEvaluator = metadataPermissionEvaluator,
        collectionService = collectionService,
        collectionPermissionEvaluator = collectionPermissionEvaluator,
        chatObjectPermissionEvaluator = chatObjectPermissionEvaluator,
    )

    private val channelId = UUID.random()

    private fun authFor(principalId: UUID): AuthenticationContext {
        val auth = mockk<AuthenticationContext>()
        val principal = AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        every { auth.principal() } returns principal
        return auth
    }

    @Test
    fun `createChannel auto-joins the creator as admin`() = runTest {
        val principalId = UUID.random()
        val callerProfileId = UUID.random()
        val callerProfile = Profile(
            id = callerProfileId,
            name = "Alice",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        val channel = ChatChannel(id = channelId, name = "general", type = ChatChannelType.GROUP)
        val auth = authFor(principalId)
        every { groupEvaluator.verifyHasMessagingAccess(auth) } just Runs
        coEvery {
            chatService.createChannel(
                groupId = null,
                name = "general",
                type = ChatChannelType.GROUP,
                attributes = null,
                initialMemberProfileId = callerProfileId,
                initialMemberRole = "admin",
            )
        } returns channel
        coEvery { profileService.getPrimaryProfile(any()) } returns callerProfile
        coEvery { chatService.canParticipate(callerProfileId) } returns true

        val result = controller.createChannel(auth, "general", ChatChannelType.GROUP, null)

        assertEquals(channelId, result.id)
        coVerify(exactly = 1) {
            chatService.createChannel(
                groupId = null,
                name = "general",
                type = ChatChannelType.GROUP,
                attributes = null,
                initialMemberProfileId = callerProfileId,
                initialMemberRole = "admin",
            )
        }
        coVerify(exactly = 0) { chatService.joinChannel(any(), any(), any()) }
    }

    @Test
    fun `createChannel requires an active primary profile`() = runTest {
        val principalId = UUID.random()
        val channel = ChatChannel(id = channelId, name = "general", type = ChatChannelType.PUBLIC)
        val auth = authFor(principalId)
        every { groupEvaluator.verifyHasMessagingAccess(auth) } just Runs
        coEvery { profileService.getPrimaryProfile(any()) } returns null

        assertFailsWith<SecurityException> {
            controller.createChannel(auth, "general", ChatChannelType.PUBLIC, null)
        }

        coVerify(exactly = 0) { chatService.createChannel(any(), any(), any(), any()) }
        coVerify(exactly = 0) { chatService.joinChannel(any(), any(), any()) }
    }

    @Test
    fun `setChannelMemberRole delegates the role change as the acting profile`() = runTest {
        val principalId = UUID.random()
        val administratorProfileId = UUID.random()
        val memberProfileId = UUID.random()
        val administrator = Profile(
            id = administratorProfileId,
            name = "Alice",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        val channel = ChatChannel(id = channelId, name = "general", type = ChatChannelType.GROUP)
        val auth = authFor(principalId)
        coEvery { profileService.getPrimaryProfile(any()) } returns administrator
        coEvery { chatService.canParticipate(administratorProfileId) } returns true
        coEvery { chatService.getById(channelId) } returns channel
        coEvery {
            chatService.setMemberRole(channelId, memberProfileId, "admin")
        } just Runs

        assertEquals(
            true,
            controller.setChannelMemberRole(
                auth,
                channelId,
                memberProfileId,
                "admin",
            ),
        )

        coVerify(exactly = 1) {
            chatService.setMemberRole(channelId, memberProfileId, "admin")
        }
    }

    @Test
    fun `removeChannelMember delegates removal as the acting profile`() = runTest {
        val administratorProfileId = UUID.random()
        val memberProfileId = UUID.random()
        val administrator = Profile(
            id = administratorProfileId,
            name = "Alice",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        val channel = ChatChannel(id = channelId, name = "general", type = ChatChannelType.GROUP)
        val auth = authFor(UUID.random())
        coEvery { profileService.getPrimaryProfile(any()) } returns administrator
        coEvery { chatService.canParticipate(administratorProfileId) } returns true
        coEvery { chatService.getById(channelId) } returns channel
        coEvery { chatService.removeMember(channelId, memberProfileId) } just Runs

        assertEquals(true, controller.removeChannelMember(auth, channelId, memberProfileId))

        coVerify(exactly = 1) {
            chatService.removeMember(channelId, memberProfileId)
        }
    }

    @Test
    fun `inviteToChannel rejects the primary profile when principal ACL passes but profile is not a member`() = runTest {
        val inviter = Profile(
            id = UUID.random(),
            name = "Alice",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        val invitee = Profile(
            id = UUID.random(),
            name = "Bob",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        val channel = ChatChannel(id = channelId, name = "Planning", type = ChatChannelType.GROUP)
        val auth = authFor(UUID.random())
        coEvery { profileService.getPrimaryProfile(any()) } returns inviter
        coEvery { chatService.canParticipate(inviter.id) } returns true
        coEvery { profileService.getById(invitee.id) } returns invitee
        coEvery { chatService.getById(channelId) } returns channel
        coEvery { chatService.getMember(channelId, inviter.id) } returns null

        assertFailsWith<SecurityException> {
            controller.inviteToChannel(auth, channelId, invitee.id)
        }

        coVerify(exactly = 1) {
            chatChannelPermissionEvaluator.verifyAllowed(auth, channel, PermissionAction.MANAGE)
        }
        coVerify(exactly = 0) { invitationService.invite(any(), any(), any()) }
    }
}

class ChatMutationControllerObjectChannelTest {

    private val chatService = mockk<ChatService>(relaxed = true)
    private val chatChannelPermissionEvaluator = mockk<ChatChannelPermissionEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>()
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val invitationService = mockk<ChatChannelInvitationService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val collectionService = mockk<CollectionService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>()
    private val chatObjectPermissionEvaluator = mockk<ChatObjectPermissionEvaluator>(relaxed = true)

    private val controller = ChatMutationController(
        chatService = chatService,
        chatChannelPermissionEvaluator = chatChannelPermissionEvaluator,
        profileService = profileService,
        profilePermissionEvaluator = profilePermissionEvaluator,
        groupEvaluator = groupEvaluator,
        invitationService = invitationService,
        metadataService = metadataService,
        metadataPermissionEvaluator = metadataPermissionEvaluator,
        collectionService = collectionService,
        collectionPermissionEvaluator = collectionPermissionEvaluator,
        chatObjectPermissionEvaluator = chatObjectPermissionEvaluator,
    )

    private val objectId = UUID.random()
    private val channelId = UUID.random()

    private fun authFor(principalId: UUID): AuthenticationContext {
        val auth = mockk<AuthenticationContext>()
        val principal = AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        every { auth.principal() } returns principal
        return auth
    }

    private fun expectedChannel() = ChatChannel(
        id = channelId,
        name = "Metadata thread",
        type = ChatChannelType.GROUP,
        objectType = ChatObjectType.METADATA,
        objectId = objectId,
    )

    @Test
    fun `objectChannel rejects callers without messaging access`() = runTest {
        val auth = authFor(UUID.random())
        every { groupEvaluator.verifyHasMessagingAccess(auth) } throws SecurityException("nope")

        assertFailsWith<SecurityException> {
            controller.objectChannel(auth, ChatObjectType.METADATA, objectId, "Metadata thread")
        }
        coVerify(exactly = 0) { chatService.getOrCreateObjectChannel(any(), any(), any()) }
    }

    @Test
    fun `objectChannel checks object permission before creating or joining a channel`() = runTest {
        val auth = authFor(UUID.random())
        val profile = Profile(
            id = UUID.random(),
            name = "Alice",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        every { groupEvaluator.verifyHasMessagingAccess(auth) } just Runs
        coEvery { profileService.getPrimaryProfile(any()) } returns profile
        coEvery { chatService.canParticipate(profile.id) } returns true
        coEvery {
            chatObjectPermissionEvaluator.verifyAllowed(auth, ChatObjectType.METADATA, objectId)
        } throws SecurityException("not allowed")

        assertFailsWith<SecurityException> {
            controller.objectChannel(auth, ChatObjectType.METADATA, objectId, "Metadata thread")
        }

        coVerify(exactly = 0) { chatService.getOrCreateObjectChannel(any(), any(), any()) }
        coVerify(exactly = 0) { chatService.joinChannel(any(), any(), any()) }
    }

    @Test
    fun `objectChannel returns the upserted channel and adds the caller as a member`() = runTest {
        val principalId = UUID.random()
        val callerProfileId = UUID.random()
        val callerProfile = Profile(
            id = callerProfileId,
            name = "Alice",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        val auth = authFor(principalId)
        every { groupEvaluator.verifyHasMessagingAccess(auth) } just Runs
        coEvery { chatService.getOrCreateObjectChannel(ChatObjectType.METADATA, objectId, "Metadata thread") } returns expectedChannel()
        coEvery { profileService.getPrimaryProfile(any()) } returns callerProfile
        coEvery { chatService.canParticipate(callerProfileId) } returns true
        coEvery { chatService.joinChannel(channelId, callerProfileId, "member") } just Runs

        val result = controller.objectChannel(auth, ChatObjectType.METADATA, objectId, "Metadata thread")

        assertEquals(channelId, result.id)
        coVerify(exactly = 1) { chatService.joinChannel(channelId, callerProfileId, "member") }
    }

    @Test
    fun `objectChannel requires an active primary profile`() = runTest {
        val principalId = UUID.random()
        val auth = authFor(principalId)
        every { groupEvaluator.verifyHasMessagingAccess(auth) } just Runs
        coEvery { profileService.getPrimaryProfile(any()) } returns null

        assertFailsWith<SecurityException> {
            controller.objectChannel(auth, ChatObjectType.METADATA, objectId, "x")
        }

        coVerify(exactly = 0) { chatService.getOrCreateObjectChannel(any(), any(), any()) }
        coVerify(exactly = 0) { chatService.joinChannel(any(), any(), any()) }
    }

    @Test
    fun `objectChannel rejects an anonymous caller`() = runTest {
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns null
        every { groupEvaluator.verifyHasMessagingAccess(auth) } just Runs

        assertFailsWith<SecurityException> {
            controller.objectChannel(auth, ChatObjectType.COLLECTION, objectId, "x")
        }

        coVerify(exactly = 0) { profileService.getPrimaryProfile(any()) }
        coVerify(exactly = 0) { chatService.getOrCreateObjectChannel(any(), any(), any()) }
        coVerify(exactly = 0) { chatService.joinChannel(any(), any(), any()) }
    }
}

class ChatMutationControllerSendMessageTest {

    private val chatService = mockk<ChatService>(relaxed = true)
    private val chatChannelPermissionEvaluator = mockk<ChatChannelPermissionEvaluator>(relaxed = true)
    private val profileService = mockk<ProfileService>()
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>(relaxed = true)
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val collectionService = mockk<CollectionService>()
    private val collectionPermissionEvaluator = mockk<CollectionPermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val chatObjectPermissionEvaluator = mockk<ChatObjectPermissionEvaluator>(relaxed = true)
    private val controller = ChatMutationController(
        chatService = chatService,
        chatChannelPermissionEvaluator = chatChannelPermissionEvaluator,
        profileService = profileService,
        profilePermissionEvaluator = profilePermissionEvaluator,
        groupEvaluator = groupEvaluator,
        invitationService = mockk(),
        metadataService = metadataService,
        metadataPermissionEvaluator = metadataPermissionEvaluator,
        collectionService = collectionService,
        collectionPermissionEvaluator = collectionPermissionEvaluator,
        chatObjectPermissionEvaluator = chatObjectPermissionEvaluator,
    )

    private val authentication = mockk<AuthenticationContext>()
    private val senderId = UUID.random()
    private val channelId = UUID.random()

    private fun prepareSend() {
        val principal = AuthenticatedPrincipal(Principal(id = UUID.random()), emptyList())
        val sender = Profile(
            id = senderId,
            name = "Alice",
            visibility = ProfileVisibility.SYSTEM,
            type = ProfileType.GENERIC,
        )
        every { authentication.principal() } returns principal
        coEvery { profileService.getPrimaryProfile(any()) } returns sender
        coEvery { chatService.canParticipate(senderId) } returns true
        coEvery { chatService.getById(channelId) } returns
            ChatChannel(id = channelId, name = "Planning", type = ChatChannelType.GROUP)
        coEvery { chatService.getMember(channelId, senderId) } returns
            ChatChannelMember(channelId, senderId, "member")
        coEvery {
            chatChannelPermissionEvaluator.verifyAllowed(authentication, any(), any())
        } just Runs
    }

    private fun metadata(id: UUID) = Metadata(
        id = id,
        name = "Private image",
        type = MetadataType.STANDARD,
        contentType = "image/jpeg",
        contentLength = null,
        languageTag = "en",
        workflowStateId = "draft",
    )

    @Test
    fun `sendMessage rejects an image id the sender cannot view`() = runTest {
        prepareSend()
        val metadataId = UUID.random()
        val metadata = metadata(metadataId)
        coEvery { metadataService.getById(metadataId) } returns metadata
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        } throws SecurityException("not allowed")
        val content = listOf(
            MessageContent(
                MessageContentType.IMAGE,
                metadataId.toString(),
            )
        )

        assertFailsWith<SecurityException> {
            controller.sendMessage(authentication, channelId, UUID.random(), content, null, null)
        }

        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage rejects the primary profile when principal ACL passes but profile is not a member`() = runTest {
        prepareSend()
        coEvery { chatService.getMember(channelId, senderId) } returns null

        assertFailsWith<SecurityException> {
            controller.sendMessage(authentication, channelId, UUID.random(), emptyList(), null, null)
        }

        coVerify(exactly = 1) {
            chatChannelPermissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.EXECUTE)
        }
        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateLastRead rejects the primary profile when principal ACL passes but profile is not a member`() = runTest {
        prepareSend()
        coEvery { chatService.getMember(channelId, senderId) } returns null

        assertFailsWith<SecurityException> {
            controller.updateLastRead(authentication, channelId, 42)
        }

        coVerify(exactly = 1) {
            chatChannelPermissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.VIEW)
        }
        coVerify(exactly = 0) { chatService.updateLastRead(any(), any(), any()) }
    }

    @Test
    fun `sendTyping rejects the primary profile when principal ACL passes but profile is not a member`() = runTest {
        prepareSend()
        coEvery { chatService.getMember(channelId, senderId) } returns null

        assertFailsWith<SecurityException> {
            controller.sendTyping(authentication, channelId, true)
        }

        coVerify(exactly = 1) {
            chatChannelPermissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.VIEW)
        }
        coVerify(exactly = 0) { chatService.sendTyping(any(), any(), any()) }
    }

    @Test
    fun `addReaction rejects the primary profile when principal ACL passes but profile is not a member`() = runTest {
        prepareSend()
        coEvery { chatService.getMember(channelId, senderId) } returns null

        assertFailsWith<SecurityException> {
            controller.addReaction(authentication, channelId, 42, "thumbsup")
        }

        coVerify(exactly = 1) {
            chatChannelPermissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.EXECUTE)
        }
        coVerify(exactly = 0) { chatService.addReaction(any(), any(), any(), any()) }
    }

    @Test
    fun `removeReaction rejects the primary profile when principal ACL passes but profile is not a member`() = runTest {
        prepareSend()
        coEvery { chatService.getMember(channelId, senderId) } returns null

        assertFailsWith<SecurityException> {
            controller.removeReaction(authentication, channelId, 42, "thumbsup")
        }

        coVerify(exactly = 1) {
            chatChannelPermissionEvaluator.verifyAllowed(authentication, any(), PermissionAction.EXECUTE)
        }
        coVerify(exactly = 0) { chatService.removeReaction(any(), any(), any(), any()) }
    }

    @Test
    fun `deleteMessage deletes a message sent by the authenticated profile`() = runTest {
        prepareSend()
        val message = ChatMessage(
            sequence = 42,
            timestamp = java.time.OffsetDateTime.now(),
            senderId = senderId,
            content = listOf(MessageContent(MessageContentType.TEXT, "remove me")),
        )
        coEvery { chatService.getMessage(channelId, 42) } returns message
        coEvery { chatService.deleteMessage(channelId, 42) } returns true

        assertEquals(true, controller.deleteMessage(authentication, channelId, 42))

        coVerify(exactly = 1) { chatService.deleteMessage(channelId, 42) }
    }

    @Test
    fun `deleteMessage rejects a message sent by another profile`() = runTest {
        prepareSend()
        coEvery { chatService.getMessage(channelId, 42) } returns ChatMessage(
            sequence = 42,
            timestamp = java.time.OffsetDateTime.now(),
            senderId = UUID.random(),
            content = listOf(MessageContent(MessageContentType.TEXT, "not mine")),
        )

        assertFailsWith<SecurityException> {
            controller.deleteMessage(authentication, channelId, 42)
        }

        coVerify(exactly = 0) { chatService.deleteMessage(any(), any()) }
    }

    @Test
    fun `deleteMessage returns false when the message does not exist in the channel`() = runTest {
        prepareSend()
        coEvery { chatService.getMessage(channelId, 404) } returns null

        assertEquals(false, controller.deleteMessage(authentication, channelId, 404))

        coVerify(exactly = 0) { chatService.deleteMessage(any(), any()) }
    }

    @Test
    fun `sendMessage checks every direct metadata backed content type`() = runTest {
        prepareSend()
        val metadataId = UUID.random()
        val metadata = metadata(metadataId)
        coEvery { metadataService.getById(metadataId) } returns metadata

        val types = listOf(
            MessageContentType.METADATA,
            MessageContentType.IMAGE,
            MessageContentType.VIDEO,
            MessageContentType.AUDIO,
            MessageContentType.FILE,
        )
        for (type in types) {
            controller.sendMessage(
                authentication,
                channelId,
                UUID.random(),
                listOf(MessageContent(type, metadataId.toString())),
                null,
                null,
            )
        }

        coVerify(exactly = types.size) {
            metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        }
        coVerify(exactly = types.size) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage checks metadata referenced by an internal image URL`() = runTest {
        prepareSend()
        val metadataId = UUID.random()
        val metadata = metadata(metadataId)
        coEvery { metadataService.getById(metadataId) } returns metadata

        controller.sendMessage(
            authentication,
            channelId,
            UUID.random(),
            listOf(MessageContent(MessageContentType.IMAGE, "https://content.example/content/image/$metadataId.webp?key=small")),
            null,
            null,
        )

        coVerify(exactly = 1) {
            metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        }
    }

    @Test
    fun `sendMessage checks direct and structured collection references`() = runTest {
        prepareSend()
        val collectionId = UUID.random()
        val collection = mockk<Collection>()
        coEvery { collectionService.getById(collectionId) } returns collection

        controller.sendMessage(
            authentication,
            channelId,
            UUID.random(),
            listOf(MessageContent(MessageContentType.COLLECTION, collectionId.toString())),
            null,
            null,
        )
        controller.sendMessage(
            authentication,
            channelId,
            UUID.random(),
            listOf(
                MessageContent(
                    MessageContentType.METADATA,
                    """{"type":"collection","id":"$collectionId"}""",
                )
            ),
            null,
            null,
        )

        coVerify(exactly = 2) {
            collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.VIEW)
        }
    }

    @Test
    fun `sendMessage checks every structured metadata reference type`() = runTest {
        prepareSend()
        val metadataId = UUID.random()
        val metadata = metadata(metadataId)
        coEvery { metadataService.getById(metadataId) } returns metadata

        val referenceTypes = listOf("metadata", "image", "video", "audio", "file")
        for (type in referenceTypes) {
            controller.sendMessage(
                authentication,
                channelId,
                UUID.random(),
                listOf(
                    MessageContent(
                        MessageContentType.METADATA,
                        """{"type":"$type","id":"$metadataId","name":"Private image"}""",
                    )
                ),
                null,
                null,
            )
        }

        coVerify(exactly = referenceTypes.size) {
            metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        }
    }

    @Test
    fun `sendMessage leaves external media URLs as non metadata content`() = runTest {
        prepareSend()
        val content = listOf(
            MessageContent(MessageContentType.IMAGE, "https://cdn.example/image.webp"),
            MessageContent(MessageContentType.VIDEO, "https://cdn.example/video.mp4"),
            MessageContent(MessageContentType.AUDIO, "https://cdn.example/audio.mp3"),
            MessageContent(MessageContentType.FILE, "https://cdn.example/document.pdf"),
        )

        controller.sendMessage(authentication, channelId, UUID.random(), content, null, null)

        coVerify(exactly = 0) { metadataService.getById(any()) }
        coVerify(exactly = 1) { chatService.sendMessage(channelId, senderId, any(), content, any(), any()) }
    }

    @Test
    fun `sendMessage rejects malformed required references`() = runTest {
        prepareSend()

        assertFailsWith<IllegalArgumentException> {
            controller.sendMessage(
                authentication,
                channelId,
                UUID.random(),
                listOf(MessageContent(MessageContentType.COLLECTION, "not-an-id")),
                null,
                null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            controller.sendMessage(
                authentication,
                channelId,
                UUID.random(),
                listOf(MessageContent(MessageContentType.METADATA, "not-a-reference")),
                null,
                null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            controller.sendMessage(
                authentication,
                channelId,
                UUID.random(),
                listOf(MessageContent(MessageContentType.METADATA, """{"type":"image"}""")),
                null,
                null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            controller.sendMessage(
                authentication,
                channelId,
                UUID.random(),
                listOf(
                    MessageContent(
                        MessageContentType.METADATA,
                        """{"type":"unsupported","id":"${UUID.random()}"}""",
                    )
                ),
                null,
                null,
            )
        }

        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sendMessage rejects metadata and collection references that do not exist`() = runTest {
        prepareSend()
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns null
        coEvery { collectionService.getById(collectionId) } returns null

        assertFailsWith<NoSuchElementException> {
            controller.sendMessage(
                authentication,
                channelId,
                UUID.random(),
                listOf(MessageContent(MessageContentType.IMAGE, metadataId.toString())),
                null,
                null,
            )
        }
        assertFailsWith<NoSuchElementException> {
            controller.sendMessage(
                authentication,
                channelId,
                UUID.random(),
                listOf(MessageContent(MessageContentType.COLLECTION, collectionId.toString())),
                null,
                null,
            )
        }

        coVerify(exactly = 0) { chatService.sendMessage(any(), any(), any(), any(), any(), any()) }
    }
}
