package bosca.nats.admin.graphql

import bosca.nats.admin.model.JetStreamApiStats
import bosca.nats.admin.model.JetStreamCluster
import bosca.nats.admin.model.JetStreamConfig
import bosca.nats.admin.model.JetStreamConsumerDetail
import bosca.nats.admin.model.JetStreamInfo
import bosca.nats.admin.model.JetStreamSequenceInfo
import bosca.nats.admin.model.JetStreamServerConfig
import bosca.nats.admin.model.JetStreamStats
import bosca.nats.admin.model.JetStreamStreamDetail
import bosca.nats.admin.model.JetStreamStreamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JetStreamApiStatsControllerTest {

    private val controller = JetStreamApiStatsController()

    @Test
    fun `total delegates to model field`() {
        val stats = JetStreamApiStats(total = 42, errors = 3)
        assertEquals(42, controller.total(stats))
    }

    @Test
    fun `errors delegates to model field`() {
        val stats = JetStreamApiStats(total = 42, errors = 3)
        assertEquals(3, controller.errors(stats))
    }

    @Test
    fun `default values are zero`() {
        val stats = JetStreamApiStats()
        assertEquals(0, controller.total(stats))
        assertEquals(0, controller.errors(stats))
    }
}

class JetStreamClusterControllerTest {

    private val controller = JetStreamClusterController()

    @Test
    fun `leader delegates to model field`() {
        val cluster = JetStreamCluster(leader = "node-a")
        assertEquals("node-a", controller.leader(cluster))
    }

    @Test
    fun `leader returns null when not set`() {
        val cluster = JetStreamCluster()
        assertNull(controller.leader(cluster))
    }
}

class JetStreamConfigControllerTest {

    private val controller = JetStreamConfigController()

    @Test
    fun `maxMemory delegates to model field`() {
        val cfg = JetStreamConfig(maxMemory = 1073741824, maxStorage = 10737418240, storeDir = "/data")
        assertEquals(1073741824, controller.maxMemory(cfg))
    }

    @Test
    fun `maxStorage delegates to model field`() {
        val cfg = JetStreamConfig(maxMemory = 0, maxStorage = 10737418240, storeDir = "/data")
        assertEquals(10737418240, controller.maxStorage(cfg))
    }

    @Test
    fun `storeDir delegates to model field`() {
        val cfg = JetStreamConfig(storeDir = "/var/nats/jetstream")
        assertEquals("/var/nats/jetstream", controller.storeDir(cfg))
    }

    @Test
    fun `defaults are zero and empty string`() {
        val cfg = JetStreamConfig()
        assertEquals(0, controller.maxMemory(cfg))
        assertEquals(0, controller.maxStorage(cfg))
        assertEquals("", controller.storeDir(cfg))
    }
}

class JetStreamConsumerDetailControllerTest {

    private val controller = JetStreamConsumerDetailController()

    private val consumer = JetStreamConsumerDetail(
        name = "my-consumer",
        streamName = "ORDERS",
        created = "2024-06-01T12:00:00Z",
        delivered = JetStreamSequenceInfo(consumerSeq = 50, streamSeq = 100),
        ackFloor = JetStreamSequenceInfo(consumerSeq = 45, streamSeq = 95),
        numAckPending = 5,
        numRedelivered = 2,
        numWaiting = 1,
        numPending = 10,
        cluster = JetStreamCluster(leader = "node-1")
    )

    @Test
    fun `name delegates to model field`() {
        assertEquals("my-consumer", controller.name(consumer))
    }

    @Test
    fun `streamName delegates to model field`() {
        assertEquals("ORDERS", controller.streamName(consumer))
    }

    @Test
    fun `created delegates to model field`() {
        assertEquals("2024-06-01T12:00:00Z", controller.created(consumer))
    }

    @Test
    fun `delivered delegates to model field`() {
        val delivered = controller.delivered(consumer)
        assertEquals(50, delivered?.consumerSeq)
        assertEquals(100, delivered?.streamSeq)
    }

    @Test
    fun `ackFloor delegates to model field`() {
        val ackFloor = controller.ackFloor(consumer)
        assertEquals(45, ackFloor?.consumerSeq)
        assertEquals(95, ackFloor?.streamSeq)
    }

    @Test
    fun `numAckPending delegates to model field`() {
        assertEquals(5, controller.numAckPending(consumer))
    }

    @Test
    fun `numRedelivered delegates to model field`() {
        assertEquals(2, controller.numRedelivered(consumer))
    }

    @Test
    fun `numWaiting delegates to model field`() {
        assertEquals(1, controller.numWaiting(consumer))
    }

    @Test
    fun `numPending delegates to model field`() {
        assertEquals(10, controller.numPending(consumer))
    }

    @Test
    fun `cluster delegates to model field`() {
        val cluster = controller.cluster(consumer)
        assertEquals("node-1", cluster?.leader)
    }

    @Test
    fun `nullable fields return null for minimal consumer`() {
        val minimal = JetStreamConsumerDetail(name = "min", streamName = "S")
        assertNull(controller.created(minimal))
        assertNull(controller.delivered(minimal))
        assertNull(controller.ackFloor(minimal))
        assertNull(controller.cluster(minimal))
    }
}

class JetStreamInfoControllerTest {

    private val controller = JetStreamInfoController()

    @Test
    fun `memory delegates to model field`() {
        val info = JetStreamInfo(memory = 1024)
        assertEquals(1024, controller.memory(info))
    }

    @Test
    fun `storage delegates to model field`() {
        val info = JetStreamInfo(storage = 4096)
        assertEquals(4096, controller.storage(info))
    }

    @Test
    fun `api returns JetStreamApiStats when set`() {
        val apiStats = JetStreamApiStats(total = 100, errors = 5)
        val info = JetStreamInfo(api = apiStats)
        val result = controller.api(info)
        assertEquals(100, result?.total)
        assertEquals(5, result?.errors)
    }

    @Test
    fun `api returns null when not set`() {
        val info = JetStreamInfo()
        assertNull(controller.api(info))
    }

    @Test
    fun `streams consumers messages bytes delegate to model`() {
        val info = JetStreamInfo(streams = 3, consumers = 7, messages = 1000, bytes = 50000)
        assertEquals(3, controller.streams(info))
        assertEquals(7, controller.consumers(info))
        assertEquals(1000, controller.messages(info))
        assertEquals(50000, controller.bytes(info))
    }
}

class JetStreamSequenceInfoControllerTest {

    private val controller = JetStreamSequenceInfoController()

    @Test
    fun `consumerSeq delegates to model field`() {
        val info = JetStreamSequenceInfo(consumerSeq = 42, streamSeq = 100)
        assertEquals(42, controller.consumerSeq(info))
    }

    @Test
    fun `streamSeq delegates to model field`() {
        val info = JetStreamSequenceInfo(consumerSeq = 42, streamSeq = 100)
        assertEquals(100, controller.streamSeq(info))
    }

    @Test
    fun `defaults are zero`() {
        val info = JetStreamSequenceInfo()
        assertEquals(0, controller.consumerSeq(info))
        assertEquals(0, controller.streamSeq(info))
    }
}

class JetStreamServerConfigControllerTest {

    private val controller = JetStreamServerConfigController()

    @Test
    fun `config delegates to model field`() {
        val cfg = JetStreamConfig(maxMemory = 1024, maxStorage = 4096, storeDir = "/data")
        val serverCfg = JetStreamServerConfig(config = cfg)
        val result = controller.config(serverCfg)
        assertEquals(1024, result?.maxMemory)
    }

    @Test
    fun `config returns null when not set`() {
        val serverCfg = JetStreamServerConfig()
        assertNull(controller.config(serverCfg))
    }

    @Test
    fun `stats delegates to model field`() {
        val stats = JetStreamStats(memory = 512, storage = 2048)
        val serverCfg = JetStreamServerConfig(stats = stats)
        val result = controller.stats(serverCfg)
        assertEquals(512, result?.memory)
    }

    @Test
    fun `stats returns null when not set`() {
        val serverCfg = JetStreamServerConfig()
        assertNull(controller.stats(serverCfg))
    }
}

class JetStreamStatsControllerTest {

    private val controller = JetStreamStatsController()

    private val stats = JetStreamStats(
        memory = 1024,
        storage = 4096,
        reservedMemory = 512,
        reservedStorage = 2048,
        accounts = 3,
        haAssets = 2,
        api = JetStreamApiStats(total = 100, errors = 5)
    )

    @Test
    fun `memory delegates to model field`() {
        assertEquals(1024, controller.memory(stats))
    }

    @Test
    fun `storage delegates to model field`() {
        assertEquals(4096, controller.storage(stats))
    }

    @Test
    fun `reservedMemory delegates to model field`() {
        assertEquals(512, controller.reservedMemory(stats))
    }

    @Test
    fun `reservedStorage delegates to model field`() {
        assertEquals(2048, controller.reservedStorage(stats))
    }

    @Test
    fun `accounts delegates to model field`() {
        assertEquals(3, controller.accounts(stats))
    }

    @Test
    fun `haAssets delegates to model field`() {
        assertEquals(2, controller.haAssets(stats))
    }

    @Test
    fun `api delegates to model field`() {
        val api = controller.api(stats)
        assertEquals(100, api?.total)
        assertEquals(5, api?.errors)
    }

    @Test
    fun `api returns null when not set`() {
        val empty = JetStreamStats()
        assertNull(controller.api(empty))
    }
}

class JetStreamStreamDetailControllerTest {

    private val controller = JetStreamStreamDetailController()

    @Test
    fun `name delegates to model field`() {
        val detail = JetStreamStreamDetail(name = "ORDERS")
        assertEquals("ORDERS", controller.name(detail))
    }

    @Test
    fun `created delegates to model field`() {
        val detail = JetStreamStreamDetail(name = "S", created = "2024-01-01T00:00:00Z")
        assertEquals("2024-01-01T00:00:00Z", controller.created(detail))
    }

    @Test
    fun `state delegates to model field`() {
        val state = JetStreamStreamState(messages = 50, bytes = 1000)
        val detail = JetStreamStreamDetail(name = "S", state = state)
        val result = controller.state(detail)
        assertEquals(50, result?.messages)
    }

    @Test
    fun `cluster delegates to model field`() {
        val cluster = JetStreamCluster(leader = "node-a")
        val detail = JetStreamStreamDetail(name = "S", cluster = cluster)
        val result = controller.cluster(detail)
        assertEquals("node-a", result?.leader)
    }

    @Test
    fun `nullable fields return null for minimal detail`() {
        val detail = JetStreamStreamDetail(name = "S")
        assertNull(controller.created(detail))
        assertNull(controller.state(detail))
        assertNull(controller.cluster(detail))
    }
}

class JetStreamStreamStateControllerTest {

    private val controller = JetStreamStreamStateController()

    private val state = JetStreamStreamState(
        messages = 500,
        bytes = 25000,
        firstSeq = 1,
        lastSeq = 500,
        consumerCount = 3,
        numSubjects = 5,
        numDeleted = 10
    )

    @Test
    fun `messages delegates to model field`() {
        assertEquals(500, controller.messages(state))
    }

    @Test
    fun `bytes delegates to model field`() {
        assertEquals(25000, controller.bytes(state))
    }

    @Test
    fun `firstSeq delegates to model field`() {
        assertEquals(1, controller.firstSeq(state))
    }

    @Test
    fun `lastSeq delegates to model field`() {
        assertEquals(500, controller.lastSeq(state))
    }

    @Test
    fun `consumerCount delegates to model field`() {
        assertEquals(3, controller.consumerCount(state))
    }

    @Test
    fun `numSubjects delegates to model field`() {
        assertEquals(5, controller.numSubjects(state))
    }

    @Test
    fun `numDeleted delegates to model field`() {
        assertEquals(10, controller.numDeleted(state))
    }
}
