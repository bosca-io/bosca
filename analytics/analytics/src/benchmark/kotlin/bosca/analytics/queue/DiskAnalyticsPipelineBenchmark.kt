package bosca.analytics.queue

import bosca.analytics.configuration.AnalyticsConfiguration
import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import bosca.analytics.repository.iceberg.IcebergEventRepository
import bosca.analytics.transform.iceberg.IcebergEventsToRecordTransform
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.ProtoBuf
import org.apache.iceberg.data.IcebergGenerics
import org.apache.iceberg.hadoop.HadoopTables
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Local durable queue-to-Parquet/Iceberg endurance; a failing earlier delivery stays pending throughout. */
object DiskAnalyticsPipelineBenchmark {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val options = args.associate { it.removePrefix("--").split('=', limit = 2).let { pair -> pair[0] to pair[1] } }
        val seconds = options["seconds"]?.toInt() ?: 600
        val rate = options["rate"]?.toInt() ?: 10_000
        require(seconds > 0 && rate > 0)
        val root = Path.of(options["root"] ?: System.getProperty("java.io.tmpdir"))
        val directory = Files.createTempDirectory(root, "analytics-pipeline-endurance-")
        val output = Path.of(checkNotNull(options["output"]))
        check(!Files.exists(output))
        val schema = AnalyticsConfiguration().eventSchema()
        val table = HadoopTables().create(schema, directory.resolve("table").toString())
        val storage = IcebergEventRepository(table, IcebergEventsToRecordTransform(schema), directory.resolve("staging").toString(), ProtoBuf)
        val producer = DiskAnalyticsEventQueue(directory.resolve("spool"), Json)
        val consumer = DiskAnalyticsEventQueue(directory.resolve("spool"), Json)
        val accepted = AtomicLong()
        val completed = AtomicLong()
        val retries = AtomicLong()
        val handoff = DiskAnalyticsBenchmarkSamples()
        val flushes = DiskAnalyticsBenchmarkSamples()
        val started = System.nanoTime()
        var producerNanos = 0L
        val windows = mutableListOf<JsonObject>()
        var quarantined = 0L
        try {
            // A dedicated failed segment must remain pending while later segments commit to real Iceberg.
            producer.enqueue(Events(events = List(10_000) { Event(0, type = EventType.Session, clientId = "failed-$it") }, sent = -1, sentMicros = 0))
            coroutineScope {
                val worker = launch(Dispatchers.IO) {
                    while (isActive) {
                        val group = consumer.receiveBatch(100, 1.seconds)
                        if (group.isEmpty()) continue
                        if (group.first().batches.first().sent == -1L) {
                            check(group.size == 1); consumer.retryBatch(group, 5.seconds); retries.incrementAndGet(); continue
                        }
                        for (delivery in group) for (batch in delivery.batches) storage.process(batch)
                        val beforeFlush = System.nanoTime()
                        storage.flush()
                        flushes.add(System.nanoTime() - beforeFlush)
                        consumer.acknowledgeBatch(group)
                        for (delivery in group) for (batch in delivery.batches) {
                            completed.addAndGet(batch.events.size.toLong())
                            handoff.add((System.currentTimeMillis() - batch.sent) * 1_000_000)
                        }
                    }
                }
                try {
                    var nextWindow = 10L
                    while (System.nanoTime() - started < seconds * 1_000_000_000L) {
                        val due = accepted.get() * 1_000_000_000L / rate
                        val remaining = due - (System.nanoTime() - started)
                        if (remaining > 0) delay((remaining / 1_000_000).coerceAtLeast(1))
                        val now = System.currentTimeMillis()
                        val base = accepted.get()
                        producer.enqueue(Events(events = List(100) { Event(now, createdMicros = 0, type = EventType.Interaction, clientId = "event-${base + it}") }, sent = now, sentMicros = 0, received = now, receivedMicros = 0))
                        accepted.addAndGet(100)
                        val elapsed = (System.nanoTime() - started) / 1_000_000_000
                        if (elapsed >= nextWindow) {
                            val status = consumer.status()
                            windows += buildJsonObject {
                                put("elapsedSeconds", elapsed); put("accepted", accepted.get()); put("committed", completed.get())
                                put("pendingEvents", status.pending)
                                put("heapUsedMiB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576.0)
                            }
                            println("PIPELINE_WINDOW ${windows.last()}"); nextWindow += 10
                        }
                    }
                    producerNanos = System.nanoTime() - started
                    producer.shutdown()
                    withTimeout(120.seconds) { while (completed.get() < accepted.get()) delay(100) }
                    val status = consumer.status()
                    check(status.pending == 10_000L)
                    quarantined = checkNotNull(status.quarantined)
                } finally { worker.cancelAndJoin() }
            }
            consumer.shutdown()
            val recovered = DiskAnalyticsEventQueue(directory.resolve("spool"), Json)
            try {
                val failed = recovered.receive(6.seconds)
                check(failed?.batches?.single()?.sent == -1L && failed.batches.single().events.size == 10_000)
                failed.acknowledge()
                check(recovered.receive(Duration.ZERO) == null)
                check(recovered.status().pending == 0L)
            } finally { recovered.shutdown() }
            storage.shutdown()
            table.refresh()
            val seen = BitSet()
            var rows = 0L
            IcebergGenerics.read(table).build().use { records ->
                for (record in records) {
                    val id = (record.getField("client_id") as String).removePrefix("event-").toInt()
                    check(!seen[id]) { "Duplicate committed event $id" }; seen.set(id); rows++
                }
            }
            check(rows == accepted.get() && seen.nextClearBit(0).toLong() == rows)
            val result = buildJsonObject {
                put("scenario", "local-iceberg-endurance"); put("java", System.getProperty("java.runtime.version"))
                put("fileStore", Files.getFileStore(root).toString()); put("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1_048_576)
                put("events", rows); put("offeredEventsPerSecond", rate)
                put("producerSeconds", producerNanos / 1e9); put("totalSeconds", (System.nanoTime() - started) / 1e9)
                put("producerEventsPerSecond", rows * 1e9 / producerNanos); put("failedSegmentRetries", retries.get())
                put("icebergSnapshots", table.snapshots().count()); put("quarantined", quarantined)
                put("handoffLatency", handoff.json()); put("icebergFlushLatency", flushes.json())
                put("windows", JsonArray(windows)); put("integrity", "passed")
                put("scope", "Local queue, protobuf staging, event record transform, Parquet and HadoopTables commits; excludes HTTP, domain transforms, remote catalog/object store and deployment volume")
            }
            Files.writeString(output, result.toString() + "\n"); println("PIPELINE_COMPLETE $result")
        } finally {
            producer.shutdown(); consumer.shutdown(); storage.shutdown()
            check(directory.toFile().deleteRecursively())
        }
    }
}
