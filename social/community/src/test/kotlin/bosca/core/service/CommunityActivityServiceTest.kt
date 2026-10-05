package bosca.core.service

import bosca.community.model.CommunityActivity
import bosca.community.repository.CommunityActivityRepository
import bosca.community.service.CommunityActivityServiceImpl
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CommunityActivityServiceTest {

    private val communityActivityRepository = mockk<CommunityActivityRepository>()
    private val communityActivityService = CommunityActivityServiceImpl(communityActivityRepository)

    @Test
    fun `createActivity should call repository`(): Unit = runBlocking {
        val groupId = UUID.random()
        val activity = CommunityActivity(
            id = UUID.random(),
            groupId = groupId,
            name = "Test Activity",
            description = "Test Description",
            type = "meeting",
            content = null,
            schedule = null
        )

        coEvery { communityActivityRepository.createActivity(groupId, "Test Activity", "Test Description", "meeting", null, null) } returns activity

        val result = communityActivityService.createActivity(groupId, "Test Activity", "Test Description", "meeting", null, null)

        assertEquals(activity, result)
        coVerify { communityActivityRepository.createActivity(groupId, "Test Activity", "Test Description", "meeting", null, null) }
    }
}
