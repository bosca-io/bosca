@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package bosca.installer.model

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid
import java.time.OffsetDateTime

class PackageInstallationModelTest {

    @Test
    fun `package definitions and installed packages round trip through their wire format`() {
        val version = PackageInstallationVersion("1.0.0", listOf("migration"))
        val installation = PackageInstallation("core", "Core", listOf(version))
        val installations = PackageInstallations(listOf(installation))

        val decodedInstallations = Json.decodeFromString<PackageInstallations>(Json.encodeToString(installations))
        assertEquals(installations.installations, decodedInstallations.installations)

        val installed = InstalledPackage("core", "1.0.0")
        assertEquals(installed, Json.decodeFromString<InstalledPackage>(Json.encodeToString(installed)))
    }

    @Test
    fun `package wire models reject absent required fields`() {
        assertFailsWith<MissingFieldException> { Json.decodeFromString<PackageInstallations>("{}") }
        assertFailsWith<MissingFieldException> {
            Json.decodeFromString<PackageInstallation>("""{"name":"Core","versions":[]}""")
        }
        assertFailsWith<MissingFieldException> {
            Json.decodeFromString<PackageInstallationVersion>("""{"installers":[]}""")
        }
        assertFailsWith<MissingFieldException> {
            Json.decodeFromString<InstalledPackage>("""{"version":"1.0.0"}""")
        }
    }

    @Test
    fun `unregistered installer names are rejected before DI lookup`() = runTest {
        val version = PackageInstallationVersion("1.0.0", listOf("registered"))
        val error = assertFailsWith<IllegalStateException> { version.getInstaller("missing") }
        assertContains(error.message.orEmpty(), "isn't registered")
    }

    // --- PackageInstallation ---

    @Test
    fun `PackageInstallation stores key name and versions`() {
        val version = PackageInstallationVersion(version = "1.0.0", installerNames = listOf("migration"))
        val installation = PackageInstallation(key = "core", name = "Core Package", versions = listOf(version))
        assertEquals("core", installation.key)
        assertEquals("Core Package", installation.name)
        assertEquals(1, installation.versions.size)
    }

    // --- PackageInstallationVersion ---

    @Test
    fun `PackageInstallationVersion stores version and installerNames`() {
        val version = PackageInstallationVersion(version = "2.0.0", installerNames = listOf("a", "b"))
        assertEquals("2.0.0", version.version)
        assertEquals(listOf("a", "b"), version.installerNames)
    }

    @Test
    fun `PackageInstallationVersion historyKey combines installation key and version`() {
        val installation = PackageInstallation(key = "pkg", name = "Package", versions = emptyList())
        val version = PackageInstallationVersion(version = "1.0.0", installerNames = emptyList())
        assertEquals("pkg:1.0.0", version.historyKey(installation))
    }

    @Test
    fun `PackageInstallationVersion installationKey combines installation key and installer name`() {
        val installation = PackageInstallation(key = "pkg", name = "Package", versions = emptyList())
        val version = PackageInstallationVersion(version = "1.0.0", installerNames = listOf("m"))
        assertEquals("pkg:m", version.installationKey(installation, "m"))
    }

    // --- PackageInstallationHistory ---

    @Test
    fun `PackageInstallationHistory id defaults to NIL`() {
        val history = PackageInstallationHistory(key = "pkg", version = "1.0.0")
        assertEquals(Uuid.NIL, history.id)
    }

    @Test
    fun `PackageInstallationHistory historyKey combines key and version`() {
        val history = PackageInstallationHistory(key = "core", version = "2.0.0")
        assertEquals("core:2.0.0", history.historyKey)
    }

    @Test
    fun `PackageInstallationHistory equality copy defaults and hashes cover every field`() {
        val created = OffsetDateTime.parse("2024-01-01T00:00:00Z")
        val base = PackageInstallationHistory(Uuid.random(), "core", "2.0.0", created)
        assertEquals(base, base)
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertFalse(base.equals(null))
        assertFalse(base.equals("history"))
        listOf(
            base.copy(id = Uuid.random()),
            base.copy(key = "other"),
            base.copy(version = "3.0.0"),
            base.copy(created = created.plusDays(1)),
        ).forEach { assertNotEquals(base, it) }
        PackageInstallationHistory(id = base.id, key = "core", version = "2.0.0").hashCode()
        PackageInstallationHistory(key = "core", version = "2.0.0", created = created).hashCode()
    }

    // --- InstalledPackage ---

    @Test
    fun `InstalledPackage stores name and version`() {
        val pkg = InstalledPackage(name = "core", version = "1.0.0")
        assertEquals("core", pkg.name)
        assertEquals("1.0.0", pkg.version)
    }

    @Test
    fun `package models cover identity and unrelated type equality branches`() {
        val version = PackageInstallationVersion("1.0.0", listOf("installer"))
        val installation = PackageInstallation("core", "Core", listOf(version))
        val installed = InstalledPackage("installer", "1.0.0")

        listOf(version, installation, installed).forEach { value ->
            assertEquals(value, value)
            assertFalse(value.equals(Any()))
            assertFalse(value.equals(null))
        }
    }
}
