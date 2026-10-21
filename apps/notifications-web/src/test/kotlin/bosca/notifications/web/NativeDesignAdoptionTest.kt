package bosca.notifications.web

import java.io.File
import kotlin.test.Test

/** Uses the production native entry point with the same isolated API and browser contracts. */
class NativeDesignAdoptionTest {
    @Test
    fun `native notification product serves shared controls assets and actions`() {
        requireNotNull(System.getProperty("adoption.native.binary"))
        val reports = File(System.getProperty("adoption.project"), "build/reports/native-design-adoption").apply { mkdirs() }
        DesignLibraryAdoptionTest().verifyProduct("", false, "native", reports)
    }
}
