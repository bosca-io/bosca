package bosca.core.graphql

import bosca.community.graphql.CommunityMessageContentExtension
import bosca.community.model.CommunityActivity
import bosca.community.model.Prayer
import bosca.community.service.CommunityActivityService
import bosca.community.service.PrayerService
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunityMessageContentExtensionTest {

    private val prayerService = mockk<PrayerService>()
    private val communityActivityService = mockk<CommunityActivityService>()
    private val controller = CommunityMessageContentExtension(prayerService, communityActivityService)

    @Test
    fun `resolves prayer when type is PRAYER`() = runTest {
        val prayerId = UUID.random()
        val content = MessageContent(MessageContentType.PRAYER, prayerId.toString())
        val prayer = mockk<Prayer>()
        coEvery { prayerService.getRequest(prayerId) } returns prayer

        val result = controller.prayer(content)

        assertEquals(prayer, result)
    }

    @Test
    fun `returns null for prayer when type is not PRAYER`() = runTest {
        val content = MessageContent(MessageContentType.TEXT, "some text")

        val result = controller.prayer(content)

        assertNull(result)
    }

    @Test
    fun `returns null for prayer when content is not a valid UUID`() = runTest {
        val content = MessageContent(MessageContentType.PRAYER, "not-a-uuid")

        val result = controller.prayer(content)

        assertNull(result)
    }

    @Test
    fun `resolves activity when type is ACTIVITY`() = runTest {
        val activityId = UUID.random()
        val content = MessageContent(MessageContentType.ACTIVITY, activityId.toString())
        val activity = mockk<CommunityActivity>()
        coEvery { communityActivityService.getActivity(activityId) } returns activity

        val result = controller.activity(content)

        assertEquals(activity, result)
    }

    @Test
    fun `returns null for activity when type is TEXT`() = runTest {
        val content = MessageContent(MessageContentType.TEXT, "some text")

        val result = controller.activity(content)

        assertNull(result)
    }

    @Test
    fun `returns null for activity when content is not a valid UUID`() = runTest {
        val content = MessageContent(MessageContentType.ACTIVITY, "not-a-uuid")

        val result = controller.activity(content)

        assertNull(result)
    }
}
