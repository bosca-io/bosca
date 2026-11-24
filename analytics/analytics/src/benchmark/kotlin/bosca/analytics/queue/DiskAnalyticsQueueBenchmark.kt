package bosca.analytics.queue

import bosca.analytics.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Opt-in application benchmark with production queue code, fsync and checksums. */
object DiskAnalyticsQueueBenchmark {
    private val json = Json { prettyPrint = false }
    private val context = EventContext(
        appId = "benchmark", appVersion = "1", sessionId = "benchmark-session", userId = "synthetic-user",
        device = Device("synthetic-installation", "Synthetic", "Browser", "web", "en-US", "macOS", "UTC", "desktop", "1"),
    )
    private val runId = UUID.randomUUID().toString()
    private var padding = ""
    private lateinit var root: Path
    private lateinit var output: Path
    private var trial = 0

    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val options = args.associate { val pair = it.removePrefix("--").split('=', limit = 2); pair[0] to pair.getOrElse(1) { "true" } }
        if (options["crash-writer"] == "true") {
            crashWriter(Path.of(checkNotNull(options["directory"])), checkNotNull(options["events"]).toInt())
            return@runBlocking
        }
        root = Path.of(options["root"] ?: System.getProperty("java.io.tmpdir"))
        Files.createDirectories(root)
        output = Path.of(options["output"] ?: root.resolve("disk-analytics-benchmark-$runId.jsonl").toString()).toAbsolutePath()
        Files.createDirectories(output.parent)
        check(!Files.exists(output)) { "Choose a fresh output file: $output" }
        padding = "x".repeat(options["padding"]?.toInt() ?: 256)
        val repeats = options["repeats"]?.toInt() ?: 3
        val events = options["events"]?.toInt() ?: 50_000
        val seconds = options["seconds"]?.toInt() ?: 10
        val mode = options["mode"] ?: "all"
        require(repeats > 0 && events > 0 && seconds > 0)
        emit(buildJsonObject {
            put("scenario", "environment"); put("runId", runId)
            put("java", System.getProperty("java.runtime.version")); put("vm", System.getProperty("java.vm.name"))
            put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
            put("processors", Runtime.getRuntime().availableProcessors()); put("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1_048_576)
            put("root", root.toAbsolutePath().toString()); put("fileStore", Files.getFileStore(root).toString())
            put("paddingBytes", padding.length); put("repeats", repeats); put("events", events); put("seconds", seconds)
            put("note", "Queue only; production fsync enabled; no Iceberg, transforms or HTTP; percentile buckets have 0.1ms resolution")
        })
        // Warm serialization, append, checksums and whole-segment delivery.
        directory("warmup") { dir ->
            val queue = queue(dir)
            try {
                repeat(50) { queue.enqueue(batch(it * 100, 100)) }
                queue.shutdown()
                val reader = queue(dir)
                try { drain(reader, 5000, 100) } finally { reader.shutdown() }
            } finally { queue.shutdown() }
        }
        val batches = options["batches"]?.split(',')?.map(String::toInt) ?: listOf(1, 10, 100, 1000)
        repeat(repeats) { index ->
            trial = index + 1
            if (mode == "all" || mode == "enqueue") for (size in batches) enqueue(events, size)
            if (mode == "all" || mode == "drain") for (size in listOf(1, 100)) drainBenchmark(minOf(events, 20_000), size, 1)
            if (mode == "all" || mode == "live") {
                if (options["rate"] != null) live(options.getValue("rate").toInt(), options["batch"]?.toInt() ?: 100, options["workers"]?.toInt() ?: 1, seconds, events)
                else {
                    live(1000, 1, 1, seconds, maxOf(events, 1000 * seconds + 1))
                    live(10_000, 100, 1, seconds, maxOf(events, 10_000 * seconds + 100))
                    live(10_000, 100, 16, seconds, maxOf(events, 10_000 * seconds + 100))
                    live(0, 100, 16, seconds, maxOf(events, 200_000))
                }
            }
            if (mode == "all" || mode == "recovery") recovery(maxOf(events, 105_000))
            if (mode == "status") statusBenchmark()
        }
        println("BENCHMARK_COMPLETE $output")
    }
    private fun queue(dir: Path, recovery: Boolean = false) = DiskAnalyticsEventQueue(dir, json,
        maxBytes = 1_073_741_824, maxSegmentAge = if (recovery) 10.minutes else 1.seconds)

    private fun batch(start: Int, size: Int): Events {
        val now = System.currentTimeMillis()
        return Events(context, List(size) { index ->
            Event(created = now / 1000, createdMicros = (now % 1000) * 1000, type = EventType.Interaction,
                clientId = "$runId-${start + index}", page = Page("/benchmark", "https://example.invalid/benchmark", "Synthetic benchmark"),
                element = Element(id = "button", type = "benchmark", extras = buildJsonObject { put("payload", padding) }))
        }, sent = now / 1000, sentMicros = (now % 1000) * 1000, received = now / 1000, receivedMicros = (now % 1000) * 1000)
    }
    private suspend fun <T> directory(name: String, block: suspend (Path) -> T): T {
        val dir = Files.createTempDirectory(root, "analytics-$name-")
        try { return block(dir) } finally { check(dir.toFile().deleteRecursively()) { "Failed to remove benchmark spool $dir" } }
    }
    private fun emit(value: JsonObject) {
        val line = json.encodeToString(JsonObject.serializer(), value)
        Files.writeString(output, "$line\n", CREATE, APPEND)
        println("BENCHMARK $line")
    }
    private fun result(scenario: String, count: Long, elapsed: Long, extra: JsonObject) = buildJsonObject {
        put("scenario", scenario); put("trial", trial); put("paddingBytes", padding.length)
        put("events", count); put("seconds", elapsed / 1e9); put("eventsPerSecond", count * 1e9 / elapsed)
        put("heapUsedMiB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576.0)
        for ((key, value) in extra) put(key, value)
    }
    private suspend fun enqueue(events: Int, size: Int) = directory("enqueue") { dir ->
        val queue = queue(dir)
        val latencies = DiskAnalyticsBenchmarkSamples()
        try {
            val start = System.nanoTime()
            var written = 0
            while (written < events) {
                val count = minOf(size, events - written)
                val payload = batch(written, count)
                val before = System.nanoTime(); queue.enqueue(payload); latencies.add(System.nanoTime() - before)
                written += count
            }
            val elapsed = System.nanoTime() - start
            val status = queue.status()
            check(status.pending <= events.toLong())
            emit(result("enqueue", written.toLong(), elapsed, buildJsonObject {
                put("batchSize", size); put("enqueueLatency", latencies.json()); put("spoolBytes", status.storageBytes)
                put("encodedBytesPerEvent", json.encodeToString(Events.serializer(), batch(0, size)).toByteArray().size.toDouble() / size)
                put("spoolFiles", Files.list(dir).use { it.count() })
            }))
        } finally { queue.shutdown() }
    }
    private suspend fun drain(queue: DiskAnalyticsEventQueue, expected: Int, group: Int): Pair<Long, JsonObject> {
        val integrity = DiskAnalyticsBenchmarkIntegrity()
        val receives = DiskAnalyticsBenchmarkSamples(); val acks = DiskAnalyticsBenchmarkSamples()
        var read = 0
        val start = System.nanoTime()
        while (read < expected) {
            val before = System.nanoTime(); val deliveries = queue.receiveBatch(group, 5.seconds); receives.add(System.nanoTime() - before)
            check(deliveries.isNotEmpty()) { "Drain stalled at $read / $expected" }
            for (delivery in deliveries) for (batch in delivery.batches) { integrity.accept(batch); read += batch.events.size }
            val ack = System.nanoTime(); queue.acknowledgeBatch(deliveries); acks.add(System.nanoTime() - ack)
        }
        val elapsed = System.nanoTime() - start
        integrity.verify(expected.toLong())
        check(queue.status().pending == 0L)
        return elapsed to buildJsonObject { put("receiveLatency", receives.json()); put("acknowledgeLatency", acks.json()); put("integrity", "passed") }
    }
    private suspend fun drainBenchmark(events: Int, size: Int, group: Int) = directory("drain") { dir ->
        val producer = queue(dir)
        try { var written = 0; while (written < events) { val count = minOf(size, events - written); producer.enqueue(batch(written, count)); written += count } }
        finally { producer.shutdown() }
        val consumer = queue(dir)
        try {
            val (elapsed, timings) = drain(consumer, events, group)
            emit(result("drain", events.toLong(), elapsed, buildJsonObject {
                put("batchSize", size); put("acknowledgeGroup", group); for ((key, value) in timings) put(key, value)
            }))
        } finally { consumer.shutdown() }
    }
    private suspend fun live(rate: Int, size: Int, workers: Int, seconds: Int, cap: Int) = directory("live") { dir ->
        val producer = queue(dir); val consumer = queue(dir)
        val produced = AtomicLong(); val consumed = AtomicLong(); val finished = AtomicBoolean()
        val integrity = DiskAnalyticsBenchmarkIntegrity()
        val enqueue = DiskAnalyticsBenchmarkSamples(); val endToEnd = DiskAnalyticsBenchmarkSamples(); val acks = DiskAnalyticsBenchmarkSamples()
        val start = System.nanoTime()
        var producerElapsed = 0L; var backlogAtStop = 0L; var maxBacklog = 0L
        val windows = mutableListOf<JsonElement>()
        val gcStart = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }
        try {
            withTimeout((seconds + 120).seconds) {
                coroutineScope {
                    val monitor = launch(Dispatchers.IO) {
                        var lastCount = 0L
                        var lastTime = start
                        while (isActive) {
                            delay(10_000)
                            val now = System.nanoTime()
                            val count = produced.get()
                            windows.add(buildJsonObject {
                                put("elapsedSeconds", (now - start) / 1e9)
                                put("eventsPerSecond", (count - lastCount) * 1e9 / (now - lastTime))
                                put("backlog", count - consumed.get())
                                put("heapUsedMiB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576.0)
                            })
                            lastCount = count; lastTime = now
                        }
                    }
                    launch(Dispatchers.IO) {
                        while (System.nanoTime() - start < seconds * 1_000_000_000L && produced.get() < cap) {
                            val count = minOf(size, cap - produced.get().toInt())
                            val payload = batch(produced.get().toInt(), count)
                            val before = System.nanoTime(); producer.enqueue(payload); enqueue.add(System.nanoTime() - before)
                            val written = produced.addAndGet(count.toLong())
                            maxBacklog = maxOf(maxBacklog, written - consumed.get())
                            if (rate > 0) {
                                val wait = start + written * 1_000_000_000L / rate - System.nanoTime()
                                if (wait > 0) delay((wait / 1_000_000).coerceAtLeast(1))
                            }
                        }
                        producerElapsed = System.nanoTime() - start
                        backlogAtStop = produced.get() - consumed.get()
                        producer.shutdown() // Seal the final partial segment before draining.
                        finished.set(true)
                        monitor.cancelAndJoin()
                    }
                    repeat(workers) {
                        launch(Dispatchers.IO) {
                            while (!finished.get() || consumed.get() < produced.get()) {
                                val deliveries = consumer.receiveBatch(100, 100.milliseconds)
                                if (deliveries.isEmpty()) continue
                                for (delivery in deliveries) for (batch in delivery.batches) integrity.accept(batch)
                                val before = System.nanoTime(); consumer.acknowledgeBatch(deliveries); acks.add(System.nanoTime() - before)
                                val now = System.currentTimeMillis()
                                for (delivery in deliveries) for (batch in delivery.batches) {
                                    val sentMillis = batch.sent * 1000 + batch.sentMicros / 1000
                                    endToEnd.add((now - sentMillis).coerceAtLeast(0) * 1_000_000)
                                    consumed.addAndGet(batch.events.size.toLong())
                                }
                            }
                        }
                    }
                }
            }
            val elapsed = System.nanoTime() - start
            integrity.verify(produced.get())
            val status = consumer.status(); check(status.pending == 0L)
            emit(result("live", produced.get(), elapsed, buildJsonObject {
                put("offeredEventsPerSecond", rate); put("batchSize", size); put("workers", workers)
                put("producerSeconds", producerElapsed / 1e9); put("producerEventsPerSecond", produced.get() * 1e9 / producerElapsed)
                put("backlogAtProducerStop", backlogAtStop); put("maxBacklog", maxBacklog); put("drainAfterStopMillis", (elapsed - producerElapsed) / 1e6)
                put("enqueueLatency", enqueue.json()); put("acknowledgeLatency", acks.json()); put("handoffLatency", endToEnd.json())
                put("remainingSpoolBytes", status.storageBytes); put("integrity", "passed")
                put("gcMillis", ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) } - gcStart)
                put("windows", JsonArray(windows))
            }))
        } finally { producer.shutdown(); consumer.shutdown() }
    }
    private suspend fun statusBenchmark() = directory("status") { dir ->
        val producer = queue(dir)
        try { repeat(50) { producer.enqueue(batch(it * 100, 100)) } } finally { producer.shutdown() }
        val observer = queue(dir)
        val latencies = DiskAnalyticsBenchmarkSamples()
        try {
            repeat(1000) {
                val before = System.nanoTime()
                check(observer.status().pending == 5000L)
                latencies.add(System.nanoTime() - before)
            }
            emit(buildJsonObject {
                put("scenario", "status"); put("trial", trial); put("calls", 1000)
                put("latency", latencies.json()); put("integrity", "passed")
            })
        } finally { observer.shutdown() }
    }
    private suspend fun recovery(events: Int) = directory("recovery") { dir ->
        val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx1g", "-cp", System.getProperty("java.class.path"),
            javaClass.name, "--crash-writer=true", "--directory=$dir", "--events=$events").inheritIO().start()
        check(withContext(Dispatchers.IO) { child.waitFor() } == 0)
        val producer = queue(dir, recovery = true)
        try {
            val start = System.nanoTime(); producer.enqueue(batch(events, 0)); val elapsed = System.nanoTime() - start
            check(producer.status().pending == events.toLong())
            val consumer = queue(dir)
            try {
                val (drainNanos, timings) = drain(consumer, events, 100)
                emit(result("recovery", events.toLong(), elapsed, buildJsonObject {
                    put("recoveryMillis", elapsed / 1e6); put("drainEventsPerSecond", events * 1e9 / drainNanos)
                    for ((key, value) in timings) put(key, value)
                }))
            } finally { consumer.shutdown() }
        } finally { producer.shutdown() }
    }
    private suspend fun crashWriter(dir: Path, events: Int) {
        padding = "x".repeat(256)
        val queue = queue(dir, recovery = true)
        var written = 0
        while (written < events) { val count = minOf(100, events - written); queue.enqueue(batch(written, count)); written += count }
        // Deliberately terminate this isolated child without closing its RAF or sealing its tail.
        Runtime.getRuntime().halt(0)
    }
}
