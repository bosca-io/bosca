package bosca.kubernetes.controller.helm

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the Helm `index.yaml` parser. The parser is the studio's
 * read path for everything in `Helm > Catalog` — repo browsing, version
 * listing, chart download URL lookup — so getting it wrong silently
 * breaks the install wizard.
 *
 * Test layout follows the four public entry points:
 *
 *   * `charts(repo, yaml, search)` — chart row list, latest-version-first,
 *     with optional case-insensitive name/description filter.
 *   * `versions(yaml, chart)` — full version history with `current=true`
 *     on index position 0.
 *   * `chartUrl(yaml, chart, version)` — download URL pinpoint.
 *   * `chartCount(yaml)` — count for the repo list view.
 *
 * The parser is intentionally defensive: malformed YAML, missing
 * `entries` block, or completely empty input all return empty/null
 * rather than throwing.
 */
class HelmIndexTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-15T12:00:00Z")

    private val sampleIndex = """
        apiVersion: v1
        entries:
          podinfo:
            - apiVersion: v2
              name: podinfo
              version: 6.11.2
              appVersion: 6.11.2
              description: Podinfo Helm chart for Kubernetes
              icon: https://stefanprodan.github.io/podinfo/cuddle_clap.gif
              urls:
                - https://stefanprodan.github.io/podinfo/podinfo-6.11.2.tgz
              created: "2026-05-14T12:00:00Z"
            - apiVersion: v2
              name: podinfo
              version: 6.11.1
              appVersion: 6.11.1
              description: Podinfo Helm chart for Kubernetes
              urls:
                - https://stefanprodan.github.io/podinfo/podinfo-6.11.1.tgz
              created: "2026-04-01T12:00:00Z"
          nginx:
            - apiVersion: v2
              name: nginx
              version: 1.0.0
              appVersion: "1.27.0"
              description: Web server
              urls:
                - https://example.com/nginx-1.0.0.tgz
              created: "2026-05-01T12:00:00Z"
    """.trimIndent()

    // ===== charts() =====

    @Test
    fun `charts returns one row per chart sorted by name with latest version`() {
        val rows = HelmIndex.charts("podinfo", sampleIndex)
        assertEquals(2, rows.size)
        assertEquals(listOf("nginx", "podinfo"), rows.map { it.name })
        val podinfo = rows.first { it.name == "podinfo" }
        assertEquals("6.11.2", podinfo.version, "first index entry wins for latest version")
        assertEquals("6.11.2", podinfo.appVersion)
        assertEquals("podinfo/podinfo", podinfo.id)
        assertEquals("podinfo", podinfo.repo)
        assertEquals("Podinfo Helm chart for Kubernetes", podinfo.description)
        assertEquals("https://stefanprodan.github.io/podinfo/cuddle_clap.gif", podinfo.icon)
    }

    @Test
    fun `charts filters case-insensitively on chart name`() {
        val rows = HelmIndex.charts("repo", sampleIndex, search = "POD")
        assertEquals(1, rows.size)
        assertEquals("podinfo", rows.single().name)
    }

    @Test
    fun `charts filters case-insensitively on description`() {
        val rows = HelmIndex.charts("repo", sampleIndex, search = "web")
        assertEquals(1, rows.size)
        assertEquals("nginx", rows.single().name)
    }

    @Test
    fun `charts blank or null search is the same as no filter`() {
        val unfiltered = HelmIndex.charts("repo", sampleIndex)
        assertEquals(unfiltered.size, HelmIndex.charts("repo", sampleIndex, search = "").size)
        assertEquals(unfiltered.size, HelmIndex.charts("repo", sampleIndex, search = "   ").size)
    }

    @Test
    fun `charts returns empty for null or blank yaml`() {
        assertEquals(emptyList(), HelmIndex.charts("r", null))
        assertEquals(emptyList(), HelmIndex.charts("r", ""))
        assertEquals(emptyList(), HelmIndex.charts("r", "   "))
    }

    @Test
    fun `charts returns empty for unparseable yaml`() {
        assertEquals(emptyList(), HelmIndex.charts("r", "::: not valid yaml :::\nentries"))
    }

    @Test
    fun `charts returns empty when yaml has no entries block`() {
        assertEquals(emptyList(), HelmIndex.charts("r", "apiVersion: v1\n"))
    }

    @Test
    fun `charts returns empty when entries is not a map`() {
        assertEquals(emptyList(), HelmIndex.charts("r", "apiVersion: v1\nentries: 42\n"))
    }

    @Test
    fun `charts skips chart whose versions list is empty`() {
        val yaml = """
            apiVersion: v1
            entries:
              ghost:
                []
        """.trimIndent()
        // The chart appears in the entries map but has no versions —
        // mapNotNull drops the row rather than emitting a partial chart.
        assertEquals(emptyList(), HelmIndex.charts("r", yaml))
    }

    @Test
    fun `charts gracefully handles missing optional fields`() {
        val yaml = """
            apiVersion: v1
            entries:
              minimal:
                - name: minimal
                  version: 0.0.1
        """.trimIndent()
        val rows = HelmIndex.charts("r", yaml)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("", row.appVersion)
        assertEquals("", row.description)
        assertNull(row.icon)
    }

    // ===== versions() =====

    @Test
    fun `versions returns every published version with current set on index 0`() {
        val versions = HelmIndex.versions(sampleIndex, "podinfo", now)
        assertEquals(2, versions.size)
        assertEquals("6.11.2", versions[0].version)
        assertTrue(versions[0].current, "first entry is the current version")
        assertEquals("6.11.1", versions[1].version)
        assertTrue(!versions[1].current)
    }

    @Test
    fun `versions formats released as a relative-time string`() {
        val versions = HelmIndex.versions(sampleIndex, "podinfo", now)
        assertEquals("1d ago", versions[0].released)
    }

    @Test
    fun `versions returns dash for entries without a created timestamp`() {
        val yaml = """
            apiVersion: v1
            entries:
              x:
                - name: x
                  version: 1.0.0
        """.trimIndent()
        assertEquals("-", HelmIndex.versions(yaml, "x", now).single().released)
    }

    @Test
    fun `versions returns empty for an unknown chart`() {
        assertEquals(emptyList(), HelmIndex.versions(sampleIndex, "nonexistent", now))
    }

    @Test
    fun `versions returns empty for null or blank index`() {
        assertEquals(emptyList(), HelmIndex.versions(null, "podinfo", now))
        assertEquals(emptyList(), HelmIndex.versions("", "podinfo", now))
    }

    // ===== chartUrl() =====

    @Test
    fun `chartUrl returns the urls index 0 entry`() {
        val url = HelmIndex.chartUrl(sampleIndex, "podinfo", "6.11.2")
        assertEquals("https://stefanprodan.github.io/podinfo/podinfo-6.11.2.tgz", url)
    }

    @Test
    fun `chartUrl returns the matching version URL for older versions`() {
        val url = HelmIndex.chartUrl(sampleIndex, "podinfo", "6.11.1")
        assertEquals("https://stefanprodan.github.io/podinfo/podinfo-6.11.1.tgz", url)
    }

    @Test
    fun `chartUrl returns null for unknown chart`() {
        assertNull(HelmIndex.chartUrl(sampleIndex, "ghost", "1.0.0"))
    }

    @Test
    fun `chartUrl returns null for unknown version`() {
        assertNull(HelmIndex.chartUrl(sampleIndex, "podinfo", "99.99.99"))
    }

    @Test
    fun `chartUrl returns null when the matched version has no urls field`() {
        val yaml = """
            apiVersion: v1
            entries:
              x:
                - name: x
                  version: 1.0.0
        """.trimIndent()
        assertNull(HelmIndex.chartUrl(yaml, "x", "1.0.0"))
    }

    @Test
    fun `chartUrl returns null for null or blank index`() {
        assertNull(HelmIndex.chartUrl(null, "podinfo", "6.11.2"))
        assertNull(HelmIndex.chartUrl("", "podinfo", "6.11.2"))
    }

    // ===== chartCount() =====

    @Test
    fun `chartCount returns the entries map size`() {
        assertEquals(2, HelmIndex.chartCount(sampleIndex))
    }

    @Test
    fun `chartCount returns 0 for null blank or malformed yaml`() {
        assertEquals(0, HelmIndex.chartCount(null))
        assertEquals(0, HelmIndex.chartCount(""))
        assertEquals(0, HelmIndex.chartCount("::: not yaml :::"))
        assertEquals(0, HelmIndex.chartCount("apiVersion: v1"))
    }
}
