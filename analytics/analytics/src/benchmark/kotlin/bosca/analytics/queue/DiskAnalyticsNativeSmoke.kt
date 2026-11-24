package bosca.analytics.queue

import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Two-process native-image check using a disposable directory supplied by the caller. */
object DiskAnalyticsNativeSmoke {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        if (args.contentEquals(arrayOf("verify"))) {
            check(System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") { "The verify phase requires the native executable" }
            val directory = Files.createTempDirectory("analytics-native-smoke-")
            try {
                val executable = ProcessHandle.current().info().command().orElseThrow()
                for (phase in listOf("prepare", "recover")) {
                    check(ProcessBuilder(executable, phase, directory.toString()).inheritIO().start().waitFor() == 0) { "Native $phase check failed" }
                }
            } finally { check(directory.toFile().deleteRecursively()) }
            return@runBlocking
        }
        require(args.size == 2) { "Usage: verify | <prepare|recover> <empty scratch directory>" }
        val directory = Path.of(args[1])
        val queue = DiskAnalyticsEventQueue(directory, Json, maxSegmentEvents = 2)
        try {
            when (args[0]) {
                "prepare" -> {
                    check(!Files.exists(directory) || Files.list(directory).use { !it.findAny().isPresent })
                    repeat(2) { id -> queue.enqueue(Events(events = listOf(Event(id.toLong(), type = EventType.Session)), sent = id.toLong(), sentMicros = 0)) }
                    val earlier = queue.receiveBatch(2, Duration.ZERO)
                    check(earlier.flatMap { it.batches }.map { it.sent }.toSet() == setOf(0L, 1L))
                    queue.retryBatch(earlier, 5.seconds)
                    repeat(18) { index ->
                        val id = index + 2L
                        queue.enqueue(Events(events = listOf(Event(id, type = EventType.Session)), sent = id, sentMicros = 0))
                    }
                    val seen = mutableSetOf<Long>()
                    while (seen.size < 18) {
                        val group = queue.receiveBatch(100, Duration.ZERO)
                        check(group.isNotEmpty())
                        for (delivery in group) for (batch in delivery.batches) check(seen.add(batch.sent) && batch.sent >= 2)
                        queue.acknowledgeBatch(group)
                    }
                    check(queue.status().pending == 2L)
                    check(queue.receive(Duration.ZERO) == null)
                }
                "recover" -> {
                    val replay = queue.receiveBatch(100, 8.seconds)
                    check(replay.flatMap { it.batches }.map { it.sent }.toSet() == setOf(0L, 1L))
                    queue.acknowledgeBatch(replay)
                    check(queue.receive(Duration.ZERO) == null)
                    check(queue.status().pending == 0L)
                }
                else -> error("Unknown phase ${args[0]}")
            }
        } finally { queue.shutdown() }
        println("NATIVE_DISK_QUEUE_${args[0].uppercase()}_PASSED")
    }
}
