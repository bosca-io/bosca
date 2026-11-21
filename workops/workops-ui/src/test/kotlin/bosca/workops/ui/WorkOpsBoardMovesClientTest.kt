package bosca.workops.ui

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkOpsBoardMovesClientTest {
    @Test fun `closing workflow requests wait for resolution outcomes and retain independent failures`() {
        val output = File.createTempFile("workops-board-moves", ".log")
        val process = ProcessBuilder(requireNotNull(System.getProperty("workops.test.node")), "--test", "src/test/client/board-moves.mjs")
            .directory(File(requireNotNull(System.getProperty("workops.test.project")))).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Board move adapter checks timed out: ${output.readText()}")
            assertEquals(0, process.exitValue(), output.readText())
        } finally { if (process.isAlive) process.destroyForcibly(); output.delete() }
    }
}
