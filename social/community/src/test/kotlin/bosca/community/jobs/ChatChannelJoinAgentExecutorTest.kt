@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.community.jobs

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelType
import bosca.community.service.ChatService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ChatChannelJoinAgentExecutorTest {

    private val chatService = mockk<ChatService>()
    private val profileService = mockk<ProfileService>()
    private val securityService = mockk<SecurityService>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<ChatService> { chatService }
        provides<ProfileService> { profileService }
        provides<SecurityService> { securityService }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `Buddy joins an eligible community channel as guide`() = runTest {
        val channelId = UUID.random()
        val groupId = UUID.random()
        val principalId = UUID.random()
        val profileId = UUID.random()
        coEvery { chatService.getById(channelId) } returns channel(channelId, groupId)
        coEvery { securityService.getPrincipalByIdentifier("Buddy") } returns Principal(id = principalId)
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile(profileId, principalId))
        coEvery { chatService.joinChannel(channelId, profileId, "guide") } returns Unit

        execute(ChatChannelJoinAgentJob(channelId, groupId))

        coVerify(exactly = 1) { chatService.joinChannel(channelId, profileId, "guide") }
    }

    @Test
    fun `Buddy join ignores a deleted profile when an active profile exists`() = runTest {
        val channelId = UUID.random()
        val groupId = UUID.random()
        val principalId = UUID.random()
        val deletedProfileId = UUID.random()
        val activeProfileId = UUID.random()
        coEvery { chatService.getById(channelId) } returns channel(channelId, groupId)
        coEvery { securityService.getPrincipalByIdentifier("Buddy") } returns Principal(id = principalId)
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(
            profile(deletedProfileId, principalId, OffsetDateTime.now()),
            profile(activeProfileId, principalId),
        )
        coEvery { chatService.joinChannel(channelId, activeProfileId, "guide") } returns Unit

        execute(ChatChannelJoinAgentJob(channelId, groupId))

        coVerify(exactly = 0) { chatService.joinChannel(channelId, deletedProfileId, any()) }
        coVerify(exactly = 1) { chatService.joinChannel(channelId, activeProfileId, "guide") }
    }

    @Test
    fun `join failure escapes so the durable job can retry`() = runTest {
        val channelId = UUID.random()
        val groupId = UUID.random()
        val principalId = UUID.random()
        val profileId = UUID.random()
        coEvery { chatService.getById(channelId) } returns channel(channelId, groupId)
        coEvery { securityService.getPrincipalByIdentifier("Buddy") } returns Principal(id = principalId)
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile(profileId, principalId))
        coEvery {
            chatService.joinChannel(channelId, profileId, "guide")
        } throws IllegalStateException("membership failed")

        assertFailsWith<IllegalStateException> {
            execute(ChatChannelJoinAgentJob(channelId, groupId))
        }
    }

    @Test
    fun `missing Buddy installation escapes so the durable job can retry`() = runTest {
        val channelId = UUID.random()
        val groupId = UUID.random()
        coEvery { chatService.getById(channelId) } returns channel(channelId, groupId)
        coEvery { securityService.getPrincipalByIdentifier("Buddy") } returns null

        assertFailsWith<IllegalStateException> {
            execute(ChatChannelJoinAgentJob(channelId, groupId))
        }

        coVerify(exactly = 0) { chatService.joinChannel(any(), any(), any()) }
    }

    private suspend fun execute(definition: ChatChannelJoinAgentJob) {
        val job = InternalJobConstructor(
            Json.encodeToJsonElement(ChatChannelJoinAgentJob.serializer(), definition),
            ChatChannelJoinAgentExecutor::class,
        )
        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            ChatChannelJoinAgentExecutor().execute()
        }
    }

    private fun channel(channelId: UUID, groupId: UUID) = ChatChannel(
        id = channelId,
        groupId = groupId,
        name = "General",
        type = ChatChannelType.GROUP,
    )

    private fun profile(
        profileId: UUID,
        principalId: UUID,
        deletedAt: OffsetDateTime? = null,
    ) = Profile(
        id = profileId,
        type = ProfileType.GENERIC,
        principal = principalId,
        name = "Buddy",
        visibility = ProfileVisibility.USER,
        deletedAt = deletedAt,
    )
}
