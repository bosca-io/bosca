package bosca.cli.update

import bosca.cli.config.CliConfigStore
import com.github.ajalt.clikt.testing.test
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UpdateSourceTest {
    @Test
    fun releaseChecksDefaultToWebsiteMetadataAndHonorExplicitSources() {
        assertEquals(UpdateChecker.DEFAULT_RELEASES_URL, UpdateChecker.releaseSource(null, null).listingUrl)
        val source = UpdateChecker.releaseSource("https://registry.example.org/prefix/raw/team/bosca-cli/", "old/repository")
        assertEquals("https://registry.example.org/prefix/raw/team/api/bosca-cli", source.listingUrl)
        assertEquals("https://registry.example.org/prefix/raw/team/bosca-cli", source.artifactsUrl)
        assertNull(source.githubRepository)
        assertEquals("https://api.github.com/repos/example/fork/releases?per_page=100",
            UpdateChecker.releaseSource(null, "example/fork").listingUrl)
    }

    @Test
    fun websiteReleaseMetadataSelectsTheArtifactUpgradeCommand() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(
                """{"version":"0.0.19","artifactsUrl":"https://registry.example.org/raw/bosca/bosca-cli"}"""
            ).build())
            val source = UpdateChecker.ReleaseSource(server.url("/cli/releases.json").toString())
            val latest = UpdateChecker.fetchLatestRelease(source)
            assertEquals(UpdateChecker.LatestRelease("0.0.19", artifactsUrl = "https://registry.example.org/raw/bosca/bosca-cli"), latest)
            assertEquals(
                "curl -fsSL 'https://registry.example.org/raw/bosca/bosca-cli/0.0.19/install.sh' | " +
                    "BOSCA_CLI_ARTIFACTS_URL='https://registry.example.org/raw/bosca/bosca-cli' sh",
                UpdateChecker.installCommand(requireNotNull(latest)),
            )
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("/cli/releases.json", request?.target)
            assertNull(request?.headers?.get("Authorization"))
        }
    }

    @Test
    fun directArtifactCheckAndVersionCommandIgnoreAliasesAndPrereleases() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(
                """{"name":"bosca-cli","versions":[{"version":"latest"},{"version":"9999.0.0-rc1"},{"version":"9999.0.0"}]}"""
            ).build())
            val repository = server.url("/raw/team/bosca-cli").toString()
            val result = VersionCommand().test("--check --artifacts-url $repository")
            assertEquals(0, result.statusCode)
            assertContains(result.output, "→ 9999.0.0")
            assertContains(result.output, repository + "/9999.0.0/install.sh")
            assertFalse(result.output.contains("github"))
            assertEquals("/raw/team/api/bosca-cli", server.takeRequest(1, TimeUnit.SECONDS)?.target)
        }
    }

    @Test
    fun legacyGitHubCacheIsDiscardedAndNewCacheIsScopedToSource() {
        val directory = Files.createTempDirectory("bosca-update-cache-").toFile()
        val previous = CliConfigStore.directoryOverride
        CliConfigStore.directoryOverride = directory
        try {
            File(directory, "update-check.json").writeText("""{"lastCheckEpochSeconds":200000,"latestVersion":"7.3.16"}""")
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body("""{"version":"0.0.19","artifactsUrl":"https://registry.example.org/raw/bosca/bosca-cli"}""").build())
                val source = UpdateChecker.ReleaseSource(server.url("/cli/releases.json").toString())
                assertEquals("0.0.19", UpdateChecker.cachedLatestRelease(source, 200000)?.version)
                assertEquals("0.0.19", UpdateChecker.cachedLatestRelease(source, 200001)?.version)
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse.Builder().body("""{"version":"0.0.20"}""").build())
                val other = UpdateChecker.ReleaseSource(server.url("/other/releases.json").toString())
                assertEquals("0.0.20", UpdateChecker.cachedLatestRelease(other, 200002)?.version)
                assertEquals(2, server.requestCount)
                assertFalse(File(directory, "update-check.json").readText().contains("7.3.16"))
            }
        } finally {
            CliConfigStore.directoryOverride = previous
            directory.deleteRecursively()
        }
    }

    @Test
    fun metadataFailuresNeverReuseOldGitHubCacheOrFallBackToGitHub() {
        val directory = Files.createTempDirectory("bosca-update-failed-cache-").toFile()
        val previous = CliConfigStore.directoryOverride
        CliConfigStore.directoryOverride = directory
        try {
            File(directory, "update-check.json").writeText("""{"lastCheckEpochSeconds":200000,"latestVersion":"7.3.16"}""")
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().code(503).build())
                val source = UpdateChecker.ReleaseSource(server.url("/cli/releases.json").toString())
                assertNull(UpdateChecker.cachedLatestRelease(source, 200000))
                assertEquals(1, server.requestCount)
            }
        } finally {
            CliConfigStore.directoryOverride = previous
            directory.deleteRecursively()
        }
    }

    @Test
    fun directArtifactLookupUsesRawRepositoryMetadata() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(
                """{"name":"bosca-cli","versions":[{"version":"0.0.20-rc1"},{"version":"0.0.19"},{"version":"0.0.18"}]}"""
            ).build())
            val repository = server.url("/raw/team/bosca-cli").toString()
            val source = UpdateChecker.releaseSource(repository, null)
            assertEquals(UpdateChecker.LatestRelease("0.0.19", artifactsUrl = repository), UpdateChecker.fetchLatestRelease(source))
            assertEquals("/raw/team/api/bosca-cli", server.takeRequest(1, TimeUnit.SECONDS)?.target)
        }
    }
}
