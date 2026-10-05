package bosca.collaboration.service

import bosca.chat.model.ChatChannelMember
import bosca.chat.service.ChatService
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MentionExtractionTest {

    private val profileId1 = Uuid.random()
    private val profileId2 = Uuid.random()
    private val chatService = mockk<ChatService>()
    private val profileService = mockk<ProfileService>()

    private val service = MentionServiceImpl(
        chatService = chatService,
        profileService = profileService,
    )

    @Test
    fun `extracts mentions from MENTION content blocks`() = runBlocking {
        val content = listOf(
            MessageContent(MessageContentType.MENTION, profileId1.toString()),
            MessageContent(MessageContentType.TEXT, "hello"),
        )
        val mentions = service.extractMentions(content)
        assertEquals(1, mentions.size)
        assertEquals(profileId1, mentions[0])
    }

    @Test
    fun `extracts mentions from text with braced format`() = runBlocking {
        val content = listOf(
            MessageContent(MessageContentType.TEXT, "Hey @{$profileId1} check this out"),
        )
        val mentions = service.extractMentions(content)
        assertEquals(1, mentions.size)
        assertEquals(profileId1, mentions[0])
    }

    @Test
    fun `extracts mentions from text with bare UUID format`() = runBlocking {
        val content = listOf(
            MessageContent(MessageContentType.TEXT, "Hey @$profileId1 check this out"),
        )
        val mentions = service.extractMentions(content)
        assertEquals(1, mentions.size)
        assertEquals(profileId1, mentions[0])
    }

    @Test
    fun `deduplicates mentions across blocks`() = runBlocking {
        val content = listOf(
            MessageContent(MessageContentType.MENTION, profileId1.toString()),
            MessageContent(MessageContentType.TEXT, "Hey @{$profileId1} and @{$profileId1}"),
        )
        val mentions = service.extractMentions(content)
        assertEquals(1, mentions.size)
    }

    @Test
    fun `extracts multiple different mentions`() = runBlocking {
        val content = listOf(
            MessageContent(MessageContentType.TEXT, "Hey @{$profileId1} and @{$profileId2}"),
        )
        val mentions = service.extractMentions(content)
        assertEquals(2, mentions.size)
        assertTrue(mentions.contains(profileId1))
        assertTrue(mentions.contains(profileId2))
    }

    @Test
    fun `resolves readable profile slug mentions from text`() = runBlocking {
        val profile = Profile(
            id = profileId1,
            type = ProfileType.GENERIC,
            principal = Uuid.random(),
            name = "Ada Lovelace",
            visibility = ProfileVisibility.USER,
        )
        coEvery { profileService.getBySlug("ada-lovelace") } returns profile
        coEvery { profileService.getByNames(any()) } returns emptyList()

        val mentions = service.extractMentions(
            listOf(MessageContent(MessageContentType.TEXT, "Ask @Ada-Lovelace.")),
        )

        assertEquals(listOf(profileId1), mentions)
        coVerify(exactly = 1) { profileService.getBySlug("ada-lovelace") }
    }

    @Test
    fun `does not treat email addresses as profile mentions`() = runBlocking {
        val mentions = service.extractMentions(
            listOf(MessageContent(MessageContentType.TEXT, "Email ada@example.com")),
        )

        assertTrue(mentions.isEmpty())
        coVerify(exactly = 0) { profileService.getBySlug(any()) }
        coVerify(exactly = 0) { profileService.getByNames(any()) }
    }

    @Test
    fun `ignores an unresolved profile slug mention`() = runBlocking {
        coEvery { profileService.getBySlug("missing-profile") } returns null
        coEvery { profileService.getByNames(any()) } returns emptyList()

        val mentions = service.extractMentions(
            listOf(MessageContent(MessageContentType.TEXT, "Ask @missing-profile")),
        )

        assertTrue(mentions.isEmpty())
    }

    @Test
    fun `resolves a multi-word profile name from plain text`() = runBlocking {
        val profile = Profile(
            id = profileId1,
            type = ProfileType.GENERIC,
            principal = Uuid.random(),
            name = "Ada Lovelace",
            visibility = ProfileVisibility.USER,
        )
        coEvery { profileService.getByNames(match { "Ada Lovelace" in it }) } returns listOf(profile)

        val mentions = service.extractMentions(
            listOf(MessageContent(MessageContentType.TEXT, "Ask @Ada Lovelace, please review this.")),
        )

        assertEquals(listOf(profileId1), mentions)
        coVerify(exactly = 0) { profileService.getBySlug(any()) }
    }

    @Test
    fun `uses an exact single-word profile name when no slug matches`() = runBlocking {
        val profile = Profile(
            id = profileId1,
            type = ProfileType.GENERIC,
            principal = Uuid.random(),
            name = "Ada",
            visibility = ProfileVisibility.USER,
        )
        coEvery { profileService.getByNames(any()) } returns listOf(profile)
        coEvery { profileService.getBySlug("ada") } returns null

        val mentions = service.extractMentions(
            listOf(MessageContent(MessageContentType.TEXT, "Ask @Ada.")),
        )

        assertEquals(listOf(profileId1), mentions)
    }

    @Test
    fun `resolves a unicode profile name from plain text`() = runBlocking {
        val profile = Profile(
            id = profileId1,
            type = ProfileType.GENERIC,
            principal = Uuid.random(),
            name = "Élodie",
            visibility = ProfileVisibility.USER,
        )
        coEvery { profileService.getByNames(any()) } returns listOf(profile)
        coEvery { profileService.getBySlug("élodie") } returns null

        val mentions = service.extractMentions(
            listOf(MessageContent(MessageContentType.TEXT, "Ask @Élodie.")),
        )

        assertEquals(listOf(profileId1), mentions)
    }

    @Test
    fun `ignores an ambiguous profile name`() = runBlocking {
        val profiles = listOf(
            Profile(
                id = profileId1,
                type = ProfileType.GENERIC,
                principal = Uuid.random(),
                name = "Ada Lovelace",
                visibility = ProfileVisibility.USER,
            ),
            Profile(
                id = profileId2,
                type = ProfileType.GENERIC,
                principal = Uuid.random(),
                name = "Ada Lovelace",
                visibility = ProfileVisibility.USER,
            ),
        )
        coEvery { profileService.getByNames(any()) } returns profiles

        val mentions = service.extractMentions(
            listOf(MessageContent(MessageContentType.TEXT, "Ask @Ada Lovelace.")),
        )

        assertTrue(mentions.isEmpty())
        coVerify(exactly = 0) { profileService.getBySlug(any()) }
    }

    @Test
    fun `returns empty list when no mentions`() = runBlocking {
        val content = listOf(
            MessageContent(MessageContentType.TEXT, "just a normal message"),
        )
        val mentions = service.extractMentions(content)
        assertTrue(mentions.isEmpty())
    }

    @Test
    fun `ignores non-text non-mention content types`() = runBlocking {
        val content = listOf(
            MessageContent(MessageContentType.IMAGE, profileId1.toString()),
        )
        val mentions = service.extractMentions(content)
        assertTrue(mentions.isEmpty())
    }

    @Test
    fun `validates mentioned membership with one targeted channel query`() = runBlocking {
        val channelId = Uuid.random()
        val chatService = mockk<ChatService>()
        val service = MentionServiceImpl(
            chatService = chatService,
            profileService = mockk(),
        )
        coEvery { chatService.getMembers(channelId, listOf(profileId1)) } returns listOf(
            ChatChannelMember(channelId, profileId1, "member"),
        )

        val result = service.validateMentions(channelId, listOf(profileId1))

        assertEquals(listOf(profileId1), result.channelMembers)
        coVerify(exactly = 0) { chatService.getMembers(channelId) }
        coVerify(exactly = 1) { chatService.getMembers(channelId, listOf(profileId1)) }
        coVerify(exactly = 0) { chatService.getMember(any(), any()) }
    }

    @Test
    fun `active nonmember is reachable without messaging group`() = runBlocking {
        val channelId = Uuid.random()
        val chatService = mockk<ChatService>()
        val profileService = mockk<ProfileService>()
        val profile = Profile(
            id = profileId1,
            type = ProfileType.GENERIC,
            principal = Uuid.random(),
            name = "Reachable profile",
            visibility = ProfileVisibility.USER,
        )
        val service = MentionServiceImpl(
            chatService = chatService,
            profileService = profileService,
        )
        coEvery { chatService.getMembers(channelId, listOf(profileId1)) } returns emptyList()
        coEvery { profileService.getAllByIds(listOf(profileId1)) } returns listOf(profile)
        coEvery { chatService.canParticipate(profileId1) } returns true

        val result = service.validateMentions(channelId, listOf(profileId1))

        assertEquals(emptyList(), result.channelMembers)
        assertEquals(listOf(profileId1), result.reachableNonMembers)
        assertEquals(emptyList(), result.unreachable)
    }

    @Test
    fun `rejects an unbounded number of mentions before querying dependencies`() = runBlocking {
        val channelId = Uuid.random()
        val chatService = mockk<ChatService>()
        val service = MentionServiceImpl(
            chatService = chatService,
            profileService = mockk(),
        )
        val profileIds = List(MentionServiceImpl.MAX_MENTIONS + 1) { Uuid.random() }

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            service.validateMentions(channelId, profileIds)
        }

        coVerify(exactly = 0) { chatService.getMembers(any(), any<List<Uuid>>()) }
    }
}
