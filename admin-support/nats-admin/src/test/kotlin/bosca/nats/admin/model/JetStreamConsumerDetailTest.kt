package bosca.nats.admin.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JetStreamConsumerDetailTest {

    @Test
    fun `JetStreamConsumerDetail stores required fields`() {
        val detail = JetStreamConsumerDetail(name = "consumer-1", streamName = "orders")
        assertEquals("consumer-1", detail.name)
        assertEquals("orders", detail.streamName)
    }

    @Test
    fun `JetStreamConsumerDetail has sensible defaults`() {
        val detail = JetStreamConsumerDetail(name = "c", streamName = "s")
        assertNull(detail.created)
        assertNull(detail.delivered)
        assertNull(detail.ackFloor)
        assertEquals(0, detail.numAckPending)
        assertEquals(0, detail.numRedelivered)
        assertEquals(0, detail.numWaiting)
        assertEquals(0, detail.numPending)
        assertNull(detail.cluster)
    }

    @Test
    fun `JetStreamConsumerDetail with delivery state`() {
        val delivered = JetStreamSequenceInfo(consumerSeq = 50, streamSeq = 100)
        val ackFloor = JetStreamSequenceInfo(consumerSeq = 45, streamSeq = 95)
        val detail = JetStreamConsumerDetail(
            name = "c1",
            streamName = "s1",
            delivered = delivered,
            ackFloor = ackFloor,
            numAckPending = 5,
            numRedelivered = 2,
            numPending = 10
        )
        assertEquals(50, detail.delivered?.consumerSeq)
        assertEquals(100, detail.delivered?.streamSeq)
        assertEquals(45, detail.ackFloor?.consumerSeq)
        assertEquals(5, detail.numAckPending)
        assertEquals(2, detail.numRedelivered)
        assertEquals(10, detail.numPending)
    }

    @Test
    fun `JetStreamSequenceInfo defaults`() {
        val info = JetStreamSequenceInfo()
        assertEquals(0, info.consumerSeq)
        assertEquals(0, info.streamSeq)
    }

    @Test
    fun `JetStreamSequenceInfo equality`() {
        val a = JetStreamSequenceInfo(consumerSeq = 10, streamSeq = 20)
        val b = JetStreamSequenceInfo(consumerSeq = 10, streamSeq = 20)
        assertEquals(a, b)
    }
}
