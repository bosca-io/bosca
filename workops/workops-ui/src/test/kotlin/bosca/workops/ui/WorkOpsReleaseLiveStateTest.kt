package bosca.workops.ui

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkOpsReleaseLiveStateTest {
    @Test fun `live session identity version scope locations and expiry retain their source authority`() {
        val output = File.createTempFile("workops-release-live-state", ".log")
        val process = ProcessBuilder(requireNotNull(System.getProperty("workops.test.node")), "--test", "src/test/client/release-live-state.mjs", "src/test/client/release-live.mjs")
            .directory(File(requireNotNull(System.getProperty("workops.test.project")))).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Live state checks timed out: ${output.readText()}")
            assertEquals(0, process.exitValue(), output.readText())
        } finally { if (process.isAlive) process.destroyForcibly(); output.delete() }
    }
}
