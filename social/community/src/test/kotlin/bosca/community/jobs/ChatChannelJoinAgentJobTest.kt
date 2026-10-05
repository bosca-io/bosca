package bosca.community.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ChatChannelJoinAgentJobTest {

    private val channelId = Uuid.random()
    private val groupId = Uuid.random()

    @Test
    fun `ChatChannelJoinAgentJob stores channelId and groupId`() {
        val job = ChatChannelJoinAgentJob(channelId = channelId, groupId = groupId)
        assertEquals(channelId, job.channelId)
        assertEquals(groupId, job.groupId)
    }

    @Test
    fun `ChatChannelJoinAgentJob allows null groupId`() {
        val job = ChatChannelJoinAgentJob(channelId = channelId, groupId = null)
        assertEquals(channelId, job.channelId)
        assertNull(job.groupId)
    }

    @Test
    fun `ChatChannelJoinAgentJob equality`() {
        val a = ChatChannelJoinAgentJob(channelId, groupId)
        val b = ChatChannelJoinAgentJob(channelId, groupId)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChatChannelJoinAgentJob copy changes channelId`() {
        val original = ChatChannelJoinAgentJob(channelId, groupId)
        val newChannel = Uuid.random()
        val copied = original.copy(channelId = newChannel)
        assertEquals(newChannel, copied.channelId)
        assertEquals(groupId, copied.groupId)
    }
}
