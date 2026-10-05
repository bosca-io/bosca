package bosca.content.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigurationTest {

    private val configuration = Configuration()

    @Test
    fun `rootCollectionPackage has correct key`() {
        val pkg = configuration.rootCollectionPackage()
        assertEquals("root-collection-installer", pkg.key)
    }

    @Test
    fun `rootCollectionPackage has correct name`() {
        val pkg = configuration.rootCollectionPackage()
        assertEquals("Root Collection Installer", pkg.name)
    }

    @Test
    fun `rootCollectionPackage has one version`() {
        val pkg = configuration.rootCollectionPackage()
        assertEquals(1, pkg.versions.size)
        assertEquals("1.0.0", pkg.versions[0].version)
    }

    @Test
    fun `rootCollectionPackage version references root-collection-installer`() {
        val pkg = configuration.rootCollectionPackage()
        val version = pkg.versions[0]
        assertTrue(version.installerNames.contains("root-collection-installer"))
    }

    @Test
    fun `biblePackage has correct key`() {
        val pkg = configuration.biblePackage()
        assertEquals("bible", pkg.key)
    }

    @Test
    fun `biblePackage has correct name`() {
        val pkg = configuration.biblePackage()
        assertEquals("Bible", pkg.name)
    }

    @Test
    fun `biblePackage has one version`() {
        val pkg = configuration.biblePackage()
        assertEquals(1, pkg.versions.size)
        assertEquals("1.0.0", pkg.versions[0].version)
    }

    @Test
    fun `biblePackage version references bibles-collection-installer`() {
        val pkg = configuration.biblePackage()
        val version = pkg.versions[0]
        assertTrue(version.installerNames.contains("bibles-collection-installer"))
    }

    @Test
    fun `rootCollectionPackage and biblePackage have different keys`() {
        val root = configuration.rootCollectionPackage()
        val bible = configuration.biblePackage()
        assertNotNull(root.key)
        assertNotNull(bible.key)
        assertTrue(root.key != bible.key)
    }
}
