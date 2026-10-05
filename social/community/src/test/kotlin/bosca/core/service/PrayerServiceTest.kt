@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.core.service

import bosca.community.model.CommunityGroup
import bosca.community.model.CommunityGroupType
import bosca.community.model.CommunityVisibility
import bosca.community.model.Prayer
import bosca.community.model.PrayerComment
import bosca.community.model.PrayerFeedFilter
import bosca.community.model.PrayerStatus
import bosca.community.events.PrayerCommentAddedEvent
import bosca.community.events.PrayerReactionAddedEvent
import bosca.community.events.PrayerReactionType
import bosca.community.repository.PrayerAnniversaryRepository
import bosca.community.repository.PrayerCommentRepository
import bosca.community.repository.PrayerCommunityGroupRepository
import bosca.community.repository.PrayerPermissionRepository
import bosca.community.repository.PrayerRepository
import bosca.community.repository.PrayerShareRepository
import bosca.community.service.CommunityService
import bosca.community.service.PrayerServiceImpl
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.events.Event
import bosca.pipelines.PipelineEventDispatcher
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrayerServiceTest {

    private val prayerRepository = mockk<PrayerRepository>()
    private val prayerCommentRepository = mockk<PrayerCommentRepository>()
    private val prayerShareRepository = mockk<PrayerShareRepository>()
    private val prayerAnniversaryRepository = mockk<PrayerAnniversaryRepository>()
    private val prayerPermissionRepository = mockk<PrayerPermissionRepository>()
    private val prayerCommunityGroupRepository = mockk<PrayerCommunityGroupRepository>()
    private val communityService = mockk<CommunityService>()
    private val service = PrayerServiceImpl(
        prayerRepository,
        prayerCommentRepository,
        prayerShareRepository,
        prayerAnniversaryRepository,
        prayerPermissionRepository,
        prayerCommunityGroupRepository,
        communityService
    )
    private val pipelineEvents = mutableListOf<Event>()

    private val now = OffsetDateTime.now()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        pipelineEvents.clear()
        provides<PipelineEventDispatcher> {
            object : PipelineEventDispatcher {
                override suspend fun <T : Event> dispatch(
                    eventName: String,
                    event: T,
                    serializer: KSerializer<T>,
                ) {
                    pipelineEvents += event
                }
            }
        }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery {
            bosca.db.transaction<Any?>(any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = it.invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    private val sampleCommunityGroupId = UUID.random()
    private val sampleProfileId = UUID.random()

    private val sampleUsersGroup = Group(
        id = UUID.random(),
        name = "community.$sampleCommunityGroupId.users",
        description = "Users",
        type = GroupType.SYSTEM,
    )

    private val sampleAdminGroup = Group(
        id = UUID.random(),
        name = "community.$sampleCommunityGroupId.administrators",
        description = "Admins",
        type = GroupType.SYSTEM,
    )

    private fun prayer(
        id: UUID = UUID.random(),
        profileId: UUID = sampleProfileId,
        status: PrayerStatus = PrayerStatus.ACTIVE,
        answeredAt: OffsetDateTime? = null
    ) = Prayer(
        id = id,
        profileId = profileId,
        title = "Test prayer",
        content = JsonPrimitive("content"),
        status = status,
        created = now,
        modified = now,
        answeredAt = answeredAt,
        lastActivityAt = now,
        attributes = null
    )

    private fun communityGroup(id: UUID = UUID.random()) = CommunityGroup(
        id = id,
        name = "Test group",
        description = "A test group",
        type = CommunityGroupType.SMALL_GROUP,
        visibility = CommunityVisibility.PRIVATE
    )

    // --- addRequest ---

    @Test
    fun `addRequest creates prayer, links community group, and grants security group permissions`(): Unit = runBlocking {
        val prayer = prayer()

        coEvery { prayerRepository.addRequest(sampleProfileId, "Test prayer", prayer.content, null) } returns prayer
        coEvery { prayerCommunityGroupRepository.addCommunityGroup(prayer.id, sampleCommunityGroupId) } returns Unit
        coEvery { communityService.getUsersGroup(sampleCommunityGroupId) } returns sampleUsersGroup
        coEvery { communityService.getAdminGroup(sampleCommunityGroupId) } returns sampleAdminGroup
        coEvery { prayerPermissionRepository.addPermission(prayer.id, sampleUsersGroup.id, PermissionAction.VIEW) } returns Unit
        coEvery { prayerPermissionRepository.addPermission(prayer.id, sampleAdminGroup.id, PermissionAction.MANAGE) } returns Unit

        val result = service.addRequest(sampleCommunityGroupId, sampleProfileId, "Test prayer", prayer.content, null)

        assertEquals(prayer, result)
        coVerify { prayerCommunityGroupRepository.addCommunityGroup(prayer.id, sampleCommunityGroupId) }
        coVerify { prayerPermissionRepository.addPermission(prayer.id, sampleUsersGroup.id, PermissionAction.VIEW) }
        coVerify { prayerPermissionRepository.addPermission(prayer.id, sampleAdminGroup.id, PermissionAction.MANAGE) }
    }

    @Test
    fun `addRequest fails when users security group is missing`(): Unit = runBlocking {
        val prayer = prayer()

        coEvery { prayerRepository.addRequest(sampleProfileId, "Test prayer", prayer.content, null) } returns prayer
        coEvery { prayerCommunityGroupRepository.addCommunityGroup(prayer.id, sampleCommunityGroupId) } returns Unit
        coEvery { communityService.getUsersGroup(sampleCommunityGroupId) } returns null

        try {
            service.addRequest(sampleCommunityGroupId, sampleProfileId, "Test prayer", prayer.content, null)
            error("Expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Users security group not found"))
        }
    }

    // --- getCommunityGroupsForPrayer ---

    @Test
    fun `getCommunityGroupsForPrayer returns all linked groups`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val groupIds = listOf(UUID.random(), UUID.random())

        coEvery { prayerCommunityGroupRepository.getCommunityGroupIds(prayerId) } returns groupIds

        assertEquals(groupIds, service.getCommunityGroupsForPrayer(prayerId))
    }

    // --- getRequests ---

    @Test
    fun `getRequests with status filter returns page`(): Unit = runBlocking {
        val groupId = UUID.random()
        val prayers = listOf(prayer(), prayer())

        coEvery { prayerRepository.getRequestsByStatus(groupId, "active", 10, 0) } returns prayers
        coEvery { prayerRepository.countRequestsByStatus(groupId, "active") } returns 2L

        val page = service.getRequests(groupId, listOf(PrayerStatus.ACTIVE), 10, 0)

        assertEquals(2, page.prayers.size)
        assertEquals(2L, page.total)
    }

    @Test
    fun `getRequests with multiple statuses returns page`(): Unit = runBlocking {
        val groupId = UUID.random()
        val prayers = listOf(prayer(), prayer(status = PrayerStatus.ANSWERED))

        coEvery { prayerRepository.getRequestsByStatus(groupId, "active,answered", 10, 0) } returns prayers
        coEvery { prayerRepository.countRequestsByStatus(groupId, "active,answered") } returns 2L

        val page = service.getRequests(groupId, listOf(PrayerStatus.ACTIVE, PrayerStatus.ANSWERED), 10, 0)

        assertEquals(2, page.prayers.size)
        assertEquals(2L, page.total)
    }

    @Test
    fun `getRequests without status filter returns all`(): Unit = runBlocking {
        val groupId = UUID.random()
        val prayers = listOf(prayer(), prayer(status = PrayerStatus.ANSWERED))

        coEvery { prayerRepository.getRequests(groupId, 50, 0) } returns prayers
        coEvery { prayerRepository.countRequests(groupId) } returns 2L

        val page = service.getRequests(groupId, null, 50, 0)

        assertEquals(2, page.prayers.size)
        assertEquals(2L, page.total)
    }

    // --- getFeed ---

    @Test
    fun `getFeed returns prayers across all user groups`(): Unit = runBlocking {
        val profileId = UUID.random()
        val group1 = communityGroup()
        val group2 = communityGroup()
        val prayers = listOf(prayer(), prayer())
        val filter = PrayerFeedFilter(listOf(group1.id, group2.id), null, 50, 0)

        coEvery { communityService.getGroups(profileId) } returns listOf(group1, group2)
        coEvery { prayerRepository.getRequestsByGroups(filter) } returns prayers
        coEvery { prayerRepository.countRequestsByGroups(filter) } returns 2L

        val page = service.getFeed(profileId, null, null, 50, 0)

        assertEquals(2, page.prayers.size)
        assertEquals(2L, page.total)
    }

    @Test
    fun `getFeed with groupIds filters to requested groups`(): Unit = runBlocking {
        val profileId = UUID.random()
        val group1 = communityGroup()
        val group2 = communityGroup()
        val prayers = listOf(prayer())
        val filter = PrayerFeedFilter(listOf(group1.id), null, 50, 0)

        coEvery { communityService.getGroups(profileId) } returns listOf(group1, group2)
        coEvery { prayerRepository.getRequestsByGroups(filter) } returns prayers
        coEvery { prayerRepository.countRequestsByGroups(filter) } returns 1L

        val page = service.getFeed(profileId, listOf(group1.id), null, 50, 0)

        assertEquals(1, page.prayers.size)
        assertEquals(1L, page.total)
    }

    @Test
    fun `getFeed with status filter`(): Unit = runBlocking {
        val profileId = UUID.random()
        val group = communityGroup()
        val prayers = listOf(prayer(status = PrayerStatus.ANSWERED))
        val filter = PrayerFeedFilter(listOf(group.id), "answered", 50, 0)

        coEvery { communityService.getGroups(profileId) } returns listOf(group)
        coEvery { prayerRepository.getRequestsByGroupsAndStatus(filter) } returns prayers
        coEvery { prayerRepository.countRequestsByGroupsAndStatus(filter) } returns 1L

        val page = service.getFeed(profileId, null, listOf(PrayerStatus.ANSWERED), 50, 0)

        assertEquals(1, page.prayers.size)
        assertEquals(1L, page.total)
    }

    @Test
    fun `getFeed returns empty when user has no groups`(): Unit = runBlocking {
        val profileId = UUID.random()

        coEvery { communityService.getGroups(profileId) } returns emptyList()

        val page = service.getFeed(profileId, null, null, 50, 0)

        assertTrue(page.prayers.isEmpty())
        assertEquals(0L, page.total)
    }

    @Test
    fun `getFeed returns empty when requested groupIds do not match user groups`(): Unit = runBlocking {
        val profileId = UUID.random()
        val userGroup = communityGroup()

        coEvery { communityService.getGroups(profileId) } returns listOf(userGroup)

        val page = service.getFeed(profileId, listOf(UUID.random()), null, 50, 0)

        assertTrue(page.prayers.isEmpty())
        assertEquals(0L, page.total)
    }

    // --- updateStatus ---

    @Test
    fun `updateStatus updates prayer status`(): Unit = runBlocking {
        val p = prayer()
        val updated = p.copy(status = PrayerStatus.CANCELLED)

        coEvery { prayerRepository.updateStatus(p.id, "cancelled") } returns updated

        assertEquals(PrayerStatus.CANCELLED, service.updateStatus(p.id, PrayerStatus.CANCELLED).status)
    }

    @Test
    fun `updateStatus to ANSWERED stamps answeredAt`(): Unit = runBlocking {
        val p = prayer()
        val updated = p.copy(status = PrayerStatus.ANSWERED, answeredAt = now)

        coEvery { prayerRepository.updateStatus(p.id, "answered") } returns updated

        val result = service.updateStatus(p.id, PrayerStatus.ANSWERED)

        assertEquals(PrayerStatus.ANSWERED, result.status)
        assertEquals(now, result.answeredAt)
    }

    // --- markPrayed / unmarkPrayed ---

    @Test
    fun `markPrayed increments count and adds tracking row`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val profileId = UUID.random()

        coEvery { prayerRepository.incrementPrayerActionCount(prayerId) } returns 3
        coEvery { prayerRepository.addPrayedBy(prayerId, profileId) } returns Unit

        assertEquals(3, service.markPrayed(prayerId, profileId))
        coVerify { prayerRepository.addPrayedBy(prayerId, profileId) }
        val event = pipelineEvents.single() as PrayerReactionAddedEvent
        assertEquals(prayerId, event.prayerId)
        assertEquals(profileId, event.reactorId)
        assertEquals(PrayerReactionType.PRAYED, event.reaction)
    }

    @Test
    fun `unmarkPrayed decrements count and removes tracking row`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val profileId = UUID.random()

        coEvery { prayerRepository.deletePrayedBy(prayerId, profileId) } returns prayerId
        coEvery { prayerRepository.decrementPrayerActionCount(prayerId) } returns 2

        assertEquals(2, service.unmarkPrayed(prayerId, profileId))
    }

    @Test
    fun `hasPrayed delegates to repository`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val profileId = UUID.random()

        coEvery { prayerRepository.hasPrayed(prayerId, profileId) } returns true

        assertTrue(service.hasPrayed(prayerId, profileId))
    }

    // --- likes ---

    @Test
    fun `likePrayer increments count and adds tracking row`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val profileId = UUID.random()

        coEvery { prayerRepository.incrementLikeCount(prayerId) } returns 5
        coEvery { prayerRepository.addLike(prayerId, profileId) } returns Unit

        assertEquals(5, service.likePrayer(prayerId, profileId))
        coVerify { prayerRepository.addLike(prayerId, profileId) }
        val event = pipelineEvents.single() as PrayerReactionAddedEvent
        assertEquals(prayerId, event.prayerId)
        assertEquals(profileId, event.reactorId)
        assertEquals(PrayerReactionType.LIKED, event.reaction)
    }

    @Test
    fun `unlikePrayer decrements count and removes tracking row`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val profileId = UUID.random()

        coEvery { prayerRepository.deleteLike(prayerId, profileId) } returns prayerId
        coEvery { prayerRepository.decrementLikeCount(prayerId) } returns 4

        assertEquals(4, service.unlikePrayer(prayerId, profileId))
    }

    @Test
    fun `hasLiked delegates to repository`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val profileId = UUID.random()

        coEvery { prayerRepository.hasLiked(prayerId, profileId) } returns true

        assertTrue(service.hasLiked(prayerId, profileId))
    }

    // --- comments ---

    @Test
    fun `addComment creates comment and increments count`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val profileId = UUID.random()
        val comment = PrayerComment(
            id = 1L, prayerId = prayerId, profileId = profileId,
            content = "Praying for you!", created = now, modified = now
        )

        coEvery { prayerCommentRepository.addComment(prayerId, null, profileId, "Praying for you!", null) } returns comment
        coEvery { prayerRepository.incrementCommentCount(prayerId) } returns 1

        val result = service.addComment(prayerId, profileId, "Praying for you!")

        assertEquals("Praying for you!", result.content)
        coVerify { prayerRepository.incrementCommentCount(prayerId) }
        assertEquals(
            PrayerCommentAddedEvent(comment.id, prayerId, profileId),
            pipelineEvents.single(),
        )
    }

    @Test
    fun `getComment delegates to repository`(): Unit = runBlocking {
        val comment = PrayerComment(
            id = 7L,
            prayerId = UUID.random(),
            profileId = UUID.random(),
            content = "A comment",
            created = now,
            modified = now,
        )
        coEvery { prayerCommentRepository.getComment(comment.id) } returns comment

        assertEquals(comment, service.getComment(comment.id))
    }

    @Test
    fun `deleteComment soft-deletes and decrements count`(): Unit = runBlocking {
        val prayerId = UUID.random()

        coEvery { prayerCommentRepository.deleteComment(5L) } returns Unit
        coEvery { prayerRepository.decrementCommentCount(prayerId) } returns 2

        service.deleteComment(prayerId, 5L)

        coVerify { prayerCommentRepository.deleteComment(5L) }
        coVerify { prayerRepository.decrementCommentCount(prayerId) }
    }

    // --- shares ---

    @Test
    fun `sharePrayer delegates to repository`(): Unit = runBlocking {
        val prayerId = UUID.random()
        val profileId = UUID.random()

        coEvery { prayerShareRepository.addShare(prayerId, profileId) } returns Unit

        service.sharePrayer(prayerId, profileId)

        coVerify { prayerShareRepository.addShare(prayerId, profileId) }
    }

    @Test
    fun `getSharedWithMe returns page`(): Unit = runBlocking {
        val profileId = UUID.random()
        val prayers = listOf(prayer())

        coEvery { prayerShareRepository.getSharedWithProfile(profileId, 50, 0) } returns prayers
        coEvery { prayerShareRepository.countSharedWithProfile(profileId) } returns 1L

        val page = service.getSharedWithMe(profileId, 50, 0)

        assertEquals(1, page.prayers.size)
        assertEquals(1L, page.total)
    }

    // --- permissions ---

    @Test
    fun `getPermissions delegates to permission repository`(): Unit = runBlocking {
        val p = prayer()

        coEvery { prayerPermissionRepository.getPermissionsByPrayerId(p.id) } returns emptyList()

        assertTrue(service.getPermissions(p).isEmpty())
    }
}
