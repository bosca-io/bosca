package bosca.nats.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JetStreamInfoTest {

    @Test
    fun `JetStreamInfo has sensible defaults`() {
        val info = JetStreamInfo()
        assertEquals(0, info.memory)
        assertEquals(0, info.storage)
        assertEquals(0, info.reservedMemory)
        assertEquals(0, info.reservedStorage)
        assertEquals(0, info.accounts)
        assertEquals(0, info.haAssets)
        assertNull(info.api)
        assertEquals(0, info.streams)
        assertEquals(0, info.consumers)
        assertEquals(0, info.messages)
        assertEquals(0, info.bytes)
        assertNull(info.accountDetails)
    }

    @Test
    fun `JetStreamInfo with account details`() {
        val streamState = JetStreamStreamState(
            messages = 100, bytes = 5000, firstSeq = 1, lastSeq = 100,
            consumerCount = 2, numSubjects = 3, numDeleted = 0
        )
        val streamDetail = JetStreamStreamDetail(
            name = "orders",
            created = "2024-01-01T00:00:00Z",
            state = streamState,
            cluster = JetStreamCluster(leader = "node-1")
        )
        val accountDetail = JetStreamAccountDetail(
            name = "\$G",
            id = "account-1",
            memory = 1024,
            storage = 4096,
            streamDetail = listOf(streamDetail)
        )
        val info = JetStreamInfo(
            streams = 1,
            consumers = 2,
            messages = 100,
            bytes = 5000,
            accountDetails = listOf(accountDetail)
        )
        assertEquals(1, info.accountDetails?.size)
        assertEquals("\$G", info.accountDetails?.first()?.name)
        assertEquals("orders", info.accountDetails?.first()?.streamDetail?.first()?.name)
        assertEquals(100, info.accountDetails?.first()?.streamDetail?.first()?.state?.messages)
    }

    @Test
    fun `JetStreamStreamState defaults`() {
        val state = JetStreamStreamState()
        assertEquals(0, state.messages)
        assertEquals(0, state.bytes)
        assertEquals(0, state.firstSeq)
        assertEquals(0, state.lastSeq)
        assertEquals(0, state.consumerCount)
        assertEquals(0, state.numSubjects)
        assertEquals(0, state.numDeleted)
    }

    @Test
    fun `JetStreamAccountDetail defaults`() {
        val detail = JetStreamAccountDetail(name = "test", id = "id-1")
        assertEquals(0, detail.memory)
        assertEquals(0, detail.storage)
        assertNull(detail.streamDetail)
    }

    @Test
    fun `JetStreamStreamDetail with null optional fields`() {
        val detail = JetStreamStreamDetail(name = "my-stream")
        assertNull(detail.created)
        assertNull(detail.state)
        assertNull(detail.cluster)
    }

    @Test
    fun `JetStreamCluster with null leader`() {
        val cluster = JetStreamCluster()
        assertNull(cluster.leader)
    }

    @Test
    fun `JetStreamCluster with leader`() {
        val cluster = JetStreamCluster(leader = "node-a")
        assertEquals("node-a", cluster.leader)
    }
}
