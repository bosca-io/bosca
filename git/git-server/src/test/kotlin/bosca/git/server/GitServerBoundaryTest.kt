package bosca.git.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class GitServerBoundaryTest {
    @Test
    fun `standalone Git runtime excludes UI Atlas and registry modules`() {
        val classpath = requireNotNull(System.getProperty("apiServerClasspath"))
        val composedModules = classpath.split(File.pathSeparator).map(::File).filter {
            it.name.startsWith("git-ui") || it.name.startsWith("bml-design") || it.name.startsWith("bml-auth-ui") || it.name.startsWith("atlas-") || it.name.startsWith("artifacts-") || it.name.startsWith("core-artifacts")
        }
        assertTrue(composedModules.isEmpty(), "Standalone Git includes composed modules: $composedModules")
    }
}
