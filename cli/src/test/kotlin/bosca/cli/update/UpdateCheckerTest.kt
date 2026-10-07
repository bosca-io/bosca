package bosca.cli.update

import bosca.cli.Version
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the update-check version logic and the `version` command.
 * These intentionally avoid the network: only the pure comparison/formatting
 * helpers and the offline `bosca version` output are exercised.
 */
class UpdateCheckerTest {

    @Test
    fun `isNewer is true for a higher patch, minor, and major`() {
        assertTrue(UpdateChecker.isNewer("5.8.5", "5.8.4"))
        assertTrue(UpdateChecker.isNewer("5.9.0", "5.8.4"))
        assertTrue(UpdateChecker.isNewer("6.0.0", "5.9.9"))
    }

    @Test
    fun `isNewer is false for equal or older versions`() {
        assertFalse(UpdateChecker.isNewer("5.8.4", "5.8.4"))
        assertFalse(UpdateChecker.isNewer("5.8.4", "5.8.5"))
        assertFalse(UpdateChecker.isNewer("5.8.4", "5.9.0"))
    }

    @Test
    fun `isNewer compares components numerically, not lexically`() {
        // "10" > "9" numerically, but "10" < "9" as strings.
        assertTrue(UpdateChecker.isNewer("5.8.10", "5.8.9"))
        assertFalse(UpdateChecker.isNewer("5.8.9", "5.8.10"))
    }

    @Test
    fun `isNewer tolerates a leading v on either side`() {
        assertTrue(UpdateChecker.isNewer("v5.8.5", "5.8.4"))
        assertFalse(UpdateChecker.isNewer("5.8.4", "v5.8.4"))
    }

    @Test
    fun `isNewer treats a shorter version as zero-padded`() {
        // 5.8 == 5.8.0, which is older than 5.8.4.
        assertFalse(UpdateChecker.isNewer("5.8", "5.8.4"))
        assertTrue(UpdateChecker.isNewer("5.9", "5.8.4"))
    }

    @Test
    fun `isNewer treats a final release as newer than its pre-release`() {
        assertTrue(UpdateChecker.isNewer("5.9.0", "5.9.0-rc1"))
        assertFalse(UpdateChecker.isNewer("5.9.0-rc1", "5.9.0"))
        // A later pre-release still beats an earlier one of the same core.
        assertTrue(UpdateChecker.isNewer("5.9.0-rc2", "5.9.0-rc1"))
    }

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        UpdateChecker.GitHubRelease(tagName = tag, draft = draft, prerelease = prerelease)

    @Test
    fun `latestReleaseVersion takes the newest CLI release among other components`() {
        // The repository's releases are shared with independently versioned components.
        val releases = listOf(release("server-v6.31.0"), release("cli-v5.9.0"), release("cli-v5.8.4"))
        assertEquals("5.9.0", UpdateChecker.latestReleaseVersion(releases))
    }

    @Test
    fun `latestReleaseVersion skips drafts and prereleases`() {
        val releases = listOf(
            release("cli-v6.0.0", draft = true),
            release("cli-v5.10.0-rc1", prerelease = true),
            release("cli-v5.9.0"),
        )
        assertEquals("5.9.0", UpdateChecker.latestReleaseVersion(releases))
    }

    @Test
    fun `latestReleaseVersion is null when no CLI release is present`() {
        assertNull(UpdateChecker.latestReleaseVersion(listOf(release("server-v6.31.0"), release("cli-vnext"))))
        assertNull(UpdateChecker.latestReleaseVersion(emptyList()))
    }

    @Test
    fun `releasesRepository defaults to the project and honors an override`() {
        assertEquals(UpdateChecker.DEFAULT_REPOSITORY, UpdateChecker.releasesRepository(null))
        assertEquals(UpdateChecker.DEFAULT_REPOSITORY, UpdateChecker.releasesRepository(" "))
        assertEquals("example/bosca", UpdateChecker.releasesRepository(" example/bosca/ "))
    }

    @Test
    fun `installCommand runs the repository's installer script`() {
        assertEquals(
            "curl -fsSL https://raw.githubusercontent.com/bosca-io/bosca/main/cli/install.sh | BOSCA_CLI_REPOSITORY=bosca-io/bosca sh",
            UpdateChecker.installCommand(UpdateChecker.DEFAULT_REPOSITORY),
        )
        assertEquals(
            "curl -fsSL https://raw.githubusercontent.com/example/bosca/main/cli/install.sh | " +
                "BOSCA_CLI_REPOSITORY=example/bosca sh",
            UpdateChecker.installCommand("example/bosca"),
        )
    }

    @Test
    fun `version resource is baked into the build and resolvable`() {
        // generateVersionResource writes the project version into the classpath,
        // so a real value (not the "dev" fallback) proves the wiring works.
        assertTrue(Version.current.isNotBlank())
        assertFalse(Version.current == Version.DEV, "version resource should be present on the test classpath")
    }

    @Test
    fun `version command prints the installed version`() {
        val result = VersionCommand().test("")
        assertEquals(0, result.statusCode)
        assertContains(result.output, "bosca ${Version.current}")
    }
}
