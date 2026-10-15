package bosca.cli.update

import bosca.cli.config.CliConfigStore
import com.github.ajalt.clikt.testing.test
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateSourceTest {
    @AfterTest
    fun resetPassiveCheck() {
        UpdateChecker.suppressPassive = false
    }

    @Test
    fun releaseChecksDefaultToPublicGitHubAndHonorExplicitSources() {
        val publicSource = UpdateChecker.releaseSource(null, null)
        assertEquals("https://api.github.com/repos/bosca-io/bosca/releases?per_page=100", publicSource.listingUrl)
        assertEquals("bosca-io/bosca", publicSource.githubRepository)
        assertNull(publicSource.artifactsUrl)
        assertEquals(publicSource, UpdateChecker.releaseSource(" ", " "))
        val source = UpdateChecker.releaseSource("https://registry.example.org/prefix/raw/team/bosca-cli/", "old/repository")
        assertEquals("https://registry.example.org/prefix/raw/team/api/bosca-cli", source.listingUrl)
        assertEquals("https://registry.example.org/prefix/raw/team/bosca-cli", source.artifactsUrl)
        assertNull(source.githubRepository)
        assertEquals("https://api.github.com/repos/example/fork/releases?per_page=100",
            UpdateChecker.releaseSource(null, "example/fork").listingUrl)
    }

    @Test
    fun githubReleaseListingSelectsStableCliReleasesAndTheirUpgradeCommand() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(
                """[
                    {"tag_name":"server-v6.31.0","draft":false,"prerelease":false},
                    {"tag_name":"0.0.36","draft":true,"prerelease":false},
                    {"tag_name":"0.0.35","draft":false,"prerelease":true},
                    {"tag_name":"0.0.34","draft":false,"prerelease":false}
                ]"""
            ).build())
            val source = githubSource(server)
            val latest = UpdateChecker.fetchLatestRelease(source)
            assertEquals(UpdateChecker.LatestRelease("0.0.34", repository = "bosca-io/bosca"), latest)
            assertEquals(
                "curl -fsSL https://raw.githubusercontent.com/bosca-io/bosca/main/cli/install.sh | " +
                    "BOSCA_CLI_REPOSITORY=bosca-io/bosca sh",
                UpdateChecker.installCommand(requireNotNull(latest)),
            )
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("/repos/bosca-io/bosca/releases?per_page=100", request?.target)
            assertEquals("application/json", request?.headers?.get("Accept"))
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
    fun unscopedCacheIsDiscardedAndNewCacheIsScopedToSource() {
        val directory = Files.createTempDirectory("bosca-update-cache-").toFile()
        val previous = CliConfigStore.directoryOverride
        CliConfigStore.directoryOverride = directory
        try {
            File(directory, "update-check.json").writeText("""{"lastCheckEpochSeconds":200000,"latestVersion":"7.3.16"}""")
            MockWebServer().use { server ->
                server.start()
                server.enqueue(githubRelease("0.0.19"))
                val source = githubSource(server)
                assertEquals("0.0.19", UpdateChecker.cachedLatestRelease(source, 200000)?.version)
                assertEquals("0.0.19", UpdateChecker.cachedLatestRelease(source, 200001)?.version)
                assertEquals(1, server.requestCount)
                server.enqueue(githubRelease("0.0.20"))
                val other = source.copy(listingUrl = server.url("/repos/team/fork/releases").toString(), githubRepository = "team/fork")
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
    fun artifactFailuresNeverReuseOldGitHubCacheOrFallBackToGitHub() {
        val directory = Files.createTempDirectory("bosca-update-failed-cache-").toFile()
        val previous = CliConfigStore.directoryOverride
        CliConfigStore.directoryOverride = directory
        try {
            File(directory, "update-check.json").writeText("""{"lastCheckEpochSeconds":200000,"latestVersion":"7.3.16"}""")
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().code(503).build())
                val source = UpdateChecker.releaseSource(server.url("/raw/team/bosca-cli").toString(), null)
                assertNull(UpdateChecker.cachedLatestRelease(source, 200000))
                assertEquals(1, server.requestCount)
            }
        } finally {
            CliConfigStore.directoryOverride = previous
            directory.deleteRecursively()
        }
    }

    @Test
    fun cachedWebsiteLookupDoesNotDelayPublicGitHubChecks() {
        val directory = Files.createTempDirectory("bosca-update-website-cache-").toFile()
        val previous = CliConfigStore.directoryOverride
        CliConfigStore.directoryOverride = directory
        try {
            File(directory, "update-check.json").writeText(
                """{"source":"https://bosca.io/cli/releases.json","lastCheckEpochSeconds":200000,"latestRelease":null}""",
            )
            MockWebServer().use { server ->
                server.start()
                server.enqueue(githubRelease("0.0.34"))
                assertEquals("0.0.34", UpdateChecker.cachedLatestRelease(githubSource(server), 200001)?.version)
                assertEquals(1, server.requestCount)
                assertFalse(File(directory, "update-check.json").readText().contains("https://bosca.io/cli/releases.json"))
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

    @Test
    fun explicitCheckFailsWhenTheSourceIsUnavailableOrInvalid() {
        MockWebServer().use { server ->
            server.start()
            val repository = server.url("/raw/team/bosca-cli").toString()
            listOf(
                MockResponse.Builder().code(503).build(),
                MockResponse.Builder().body("not json").build(),
                MockResponse.Builder().body("""{"versions":[]}""").build(),
            ).forEach { response ->
                server.enqueue(response)
                val result = VersionCommand().test("--check --artifacts-url $repository")
                assertTrue(result.statusCode != 0, result.output)
                assertContains(result.stderr, "Could not find a bosca release")
                assertFalse(result.output.contains("No newer release"))
            }
            assertEquals(3, server.requestCount)
        }
        val invalid = VersionCommand().test("--check --artifacts-url https://example.org/wrong-path")
        assertTrue(invalid.statusCode != 0)
        assertContains(invalid.stderr, "must be a raw repository download URL")
    }

    @Test
    fun explicitChecksAlwaysFetchAndDoNotClaimAnOlderReleaseIsInstalled() {
        MockWebServer().use { server ->
            server.start()
            val repository = server.url("/raw/team/bosca-cli").toString()
            repeat(2) {
                server.enqueue(MockResponse.Builder().body("""{"versions":[{"version":"0.0.0"}]}""").build())
                val result = VersionCommand().test("--check --artifacts-url $repository")
                assertEquals(0, result.statusCode)
                assertContains(result.output, "No newer release is available (latest published: 0.0.0)")
                assertFalse(result.output.contains("You are on the latest release"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun cacheRefreshesDailyAndRetainsTheLastReleaseDuringAnOutage() {
        val directory = Files.createTempDirectory("bosca-update-refresh-").toFile()
        val previous = CliConfigStore.directoryOverride
        CliConfigStore.directoryOverride = directory
        try {
            MockWebServer().use { server ->
                server.start()
                val source = githubSource(server)
                server.enqueue(githubRelease("1.0.0"))
                assertEquals("1.0.0", UpdateChecker.cachedLatestRelease(source, 200000)?.version)
                assertEquals("1.0.0", UpdateChecker.cachedLatestRelease(source, 286399)?.version)
                assertEquals(1, server.requestCount)

                server.enqueue(MockResponse.Builder().code(503).build())
                assertEquals("1.0.0", UpdateChecker.cachedLatestRelease(source, 286400)?.version)
                assertEquals("1.0.0", UpdateChecker.cachedLatestRelease(source, 286401)?.version)
                assertEquals(2, server.requestCount)

                server.enqueue(githubRelease("1.0.1"))
                assertEquals("1.0.1", UpdateChecker.cachedLatestRelease(source, 372800)?.version)
                assertEquals(3, server.requestCount)
            }
        } finally {
            CliConfigStore.directoryOverride = previous
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptCachesAndClockChangesDoNotSuppressChecks() {
        val directory = Files.createTempDirectory("bosca-update-clock-").toFile()
        val previous = CliConfigStore.directoryOverride
        CliConfigStore.directoryOverride = directory
        try {
            File(directory, "update-check.json").writeText("partial json")
            MockWebServer().use { server ->
                server.start()
                val source = githubSource(server)
                server.enqueue(githubRelease("1.0.0"))
                assertEquals("1.0.0", UpdateChecker.cachedLatestRelease(source, 200000)?.version)
                server.enqueue(githubRelease("1.0.1"))
                assertEquals("1.0.1", UpdateChecker.cachedLatestRelease(source, 199999)?.version)
                assertEquals(2, server.requestCount)
            }
        } finally {
            CliConfigStore.directoryOverride = previous
            directory.deleteRecursively()
        }
    }

    @Test
    fun stalledReleaseRequestsTimeOut() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder()
                .headersDelay(5, TimeUnit.SECONDS)
                .body("""[{"tag_name":"1.0.0","draft":false,"prerelease":false}]""")
                .build())
            val source = githubSource(server)
            assertNull(UpdateChecker.fetchLatestRelease(source))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun stalledReleaseBodiesTimeOut() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder()
                .bodyDelay(5, TimeUnit.SECONDS)
                .body("""[{"tag_name":"1.0.0","draft":false,"prerelease":false}]""")
                .build())
            val source = githubSource(server)
            assertNull(UpdateChecker.fetchLatestRelease(source))
            assertEquals(1, server.requestCount)
        }
    }

    private fun githubSource(server: MockWebServer) = UpdateChecker.releaseSource(null, null).copy(
        listingUrl = server.url("/repos/bosca-io/bosca/releases?per_page=100").toString(),
    )

    private fun githubRelease(version: String) = MockResponse.Builder().body(
        """[{"tag_name":"$version","draft":false,"prerelease":false}]""",
    ).build()
}
