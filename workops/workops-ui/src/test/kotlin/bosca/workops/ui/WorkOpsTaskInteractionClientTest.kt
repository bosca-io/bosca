package bosca.workops.ui

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkOpsTaskInteractionClientTest {
    @Test fun `live query feedback and task keyboard navigation retain current drafts and native behavior`() {
        val output = File.createTempFile("workops-task-interactions", ".log")
        val process = ProcessBuilder(requireNotNull(System.getProperty("workops.test.node")), "--test", "src/test/client/task-interactions.mjs")
            .directory(File(requireNotNull(System.getProperty("workops.test.project")))).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Task interaction checks timed out: ${output.readText()}")
            assertEquals(0, process.exitValue(), output.readText())
        } finally { if (process.isAlive) process.destroyForcibly(); output.delete() }
    }
}
