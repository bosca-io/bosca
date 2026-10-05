package bosca.installer.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class PackageInstallationTest {

    // --- PackageInstallation ---

    @Test
    fun `PackageInstallation stores key name and versions`() {
        val v1 = PackageInstallationVersion(version = "1.0.0", installerNames = listOf("migration"))
        val v2 = PackageInstallationVersion(version = "2.0.0", installerNames = listOf("migration", "seed"))
        val installation = PackageInstallation(key = "core", name = "Core Package", versions = listOf(v1, v2))
        assertEquals("core", installation.key)
        assertEquals("Core Package", installation.name)
        assertEquals(2, installation.versions.size)
    }

    @Test
    fun `PackageInstallation equality is based on all fields`() {
        val versions = listOf(PackageInstallationVersion(version = "1.0.0", installerNames = emptyList()))
        val i1 = PackageInstallation(key = "k", name = "n", versions = versions)
        val i2 = PackageInstallation(key = "k", name = "n", versions = versions)
        assertEquals(i1, i2)
        assertEquals(i1.hashCode(), i2.hashCode())
    }

    @Test
    fun `PackageInstallation inequality when key differs`() {
        val versions = listOf(PackageInstallationVersion(version = "1.0.0", installerNames = emptyList()))
        val i1 = PackageInstallation(key = "a", name = "n", versions = versions)
        val i2 = PackageInstallation(key = "b", name = "n", versions = versions)
        assertNotEquals(i1, i2)
    }

    @Test
    fun `PackageInstallation with empty versions list`() {
        val installation = PackageInstallation(key = "empty", name = "Empty Package", versions = emptyList())
        assertEquals(0, installation.versions.size)
    }

    // --- PackageInstallationVersion ---

    @Test
    fun `PackageInstallationVersion historyKey with installer name and version`() {
        val installation = PackageInstallation(key = "pkg", name = "Package", versions = emptyList())
        val installer = object : bosca.installer.service.PackageInstaller {
            override val version: String = "v2"
            override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {}
        }
        val version = PackageInstallationVersion(version = "1.0.0", installerNames = listOf("migration"))
        assertEquals("pkg:migration:v2", version.historyKey(installation, "migration", installer))
    }

    @Test
    fun `PackageInstallationVersion equality is based on all fields`() {
        val v1 = PackageInstallationVersion(version = "1.0.0", installerNames = listOf("a", "b"))
        val v2 = PackageInstallationVersion(version = "1.0.0", installerNames = listOf("a", "b"))
        assertEquals(v1, v2)
    }

    @Test
    fun `PackageInstallationVersion inequality when version differs`() {
        val v1 = PackageInstallationVersion(version = "1.0.0", installerNames = emptyList())
        val v2 = PackageInstallationVersion(version = "2.0.0", installerNames = emptyList())
        assertNotEquals(v1, v2)
    }

    // --- PackageInstallationHistory ---

    @Test
    fun `PackageInstallationHistory historyKey format`() {
        val history = PackageInstallationHistory(key = "content", version = "3.0.0")
        assertEquals("content:3.0.0", history.historyKey)
    }

    @Test
    fun `PackageInstallationHistory with custom id`() {
        val id = Uuid.random()
        val history = PackageInstallationHistory(id = id, key = "pkg", version = "1.0.0")
        assertEquals(id, history.id)
    }

    @Test
    fun `PackageInstallationHistory equality is based on all relevant fields`() {
        val now = java.time.OffsetDateTime.now()
        val h1 = PackageInstallationHistory(id = Uuid.NIL, key = "k", version = "v", created = now)
        val h2 = PackageInstallationHistory(id = Uuid.NIL, key = "k", version = "v", created = now)
        assertEquals(h1, h2)
    }

    // --- InstalledPackage ---

    @Test
    fun `InstalledPackage equality is based on name and version`() {
        val p1 = InstalledPackage(name = "core", version = "1.0.0")
        val p2 = InstalledPackage(name = "core", version = "1.0.0")
        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
    }

    @Test
    fun `InstalledPackage inequality when version differs`() {
        val p1 = InstalledPackage(name = "core", version = "1.0.0")
        val p2 = InstalledPackage(name = "core", version = "2.0.0")
        assertNotEquals(p1, p2)
    }
}
