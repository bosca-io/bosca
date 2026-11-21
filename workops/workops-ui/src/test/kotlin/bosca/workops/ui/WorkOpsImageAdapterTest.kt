package bosca.workops.ui

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkOpsImageAdapterTest {
    @Test fun `browser adapter retains upload identity across cancellation and completion retry`() {
        val process = ProcessBuilder(requireNotNull(System.getProperty("workops.test.node")), "--test", "src/test/client/images.mjs")
            .directory(File(requireNotNull(System.getProperty("workops.test.project")))).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Image adapter checks timed out")
        assertEquals(0, process.exitValue(), output)
    }
}
