package bosca.analytics.queue

import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Duration

class DiskAnalyticsOwnershipTest {
    @Test fun `producer and consumer ownership reject another process and recover after it exits`(): Unit = runBlocking {
        for (role in listOf("producer", "consumer")) {
            val directory = Files.createTempDirectory("analytics-role-$role")
            val queue = DiskAnalyticsEventQueue(directory, Json, maxSegmentEvents = 1)
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val classes = Path.of(DiskAnalyticsRoleHolder::class.java.protectionDomain.codeSource.location.toURI()).toString()
            val process = ProcessBuilder(java, "-cp", classes, DiskAnalyticsRoleHolder::class.java.name, directory.toString(), role)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try {
                assertEquals("LOCKED", process.inputStream.bufferedReader().readLine())
                val events = Events(events = listOf(Event(1, type = EventType.Session)), sent = 1, sentMicros = 0)
                if (role == "producer") assertFailsWith<IllegalStateException> { queue.enqueue(events) }
                else {
                    queue.enqueue(events)
                    assertFailsWith<IllegalStateException> { queue.receive(Duration.ZERO) }
                }
                process.outputStream.write(1); process.outputStream.flush()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                if (role == "producer") queue.enqueue(events)
                assertNotNull(queue.receive(Duration.ZERO)).acknowledge()
                assertNull(queue.receive(Duration.ZERO))
            } finally {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
                queue.shutdown(); check(directory.toFile().deleteRecursively())
            }
        }
    }
}
