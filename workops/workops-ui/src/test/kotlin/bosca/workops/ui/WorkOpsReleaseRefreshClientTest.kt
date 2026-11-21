package bosca.workops.ui

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkOpsReleaseRefreshClientTest {
    @Test fun `preserved release sections refresh coalesce defer and dispose without replacing drafts`() {
        val output = File.createTempFile("workops-release-refresh", ".log")
        val process = ProcessBuilder(requireNotNull(System.getProperty("workops.test.node")), "--test", "src/test/client/release-refresh.mjs")
            .directory(File(requireNotNull(System.getProperty("workops.test.project")))).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Release refresh checks timed out: ${output.readText()}")
            assertEquals(0, process.exitValue(), output.readText())
        } finally { if (process.isAlive) process.destroyForcibly(); output.delete() }
    }
}
