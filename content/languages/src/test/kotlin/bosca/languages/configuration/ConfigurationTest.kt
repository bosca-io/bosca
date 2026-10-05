package bosca.languages.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigurationTest {

    private val config = Configuration()

    @Test
    fun `languagesPackage returns package with correct key`() {
        val pkg = config.languagesPackage()
        assertEquals("languages", pkg.key)
    }

    @Test
    fun `languagesPackage returns package with correct name`() {
        val pkg = config.languagesPackage()
        assertEquals("Languages", pkg.name)
    }

    @Test
    fun `languagesPackage includes every installer version in order`() {
        val pkg = config.languagesPackage()
        assertEquals(listOf("1.0.0", "1.1.0", "1.2.0"), pkg.versions.map { it.version })
    }

    @Test
    fun `every languagesPackage version references only language-installer`() {
        val pkg = config.languagesPackage()
        assertTrue(pkg.versions.isNotEmpty())
        pkg.versions.forEach { version ->
            assertEquals(listOf("language-installer"), version.installerNames)
        }
    }
}
