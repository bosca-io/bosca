package bosca.kubernetes.controller.helm

import bosca.kubernetes.controller.util.formatRelativeTime
import bosca.kubernetes.model.K8sHelmChart
import bosca.kubernetes.model.K8sHelmChartVersion
import org.yaml.snakeyaml.Yaml
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Parses a Helm repository `index.yaml` into chart/version views.
 *
 * The index format (see https://helm.sh/docs/topics/chart_repository/):
 *
 *   apiVersion: v1
 *   entries:
 *     <chart-name>:
 *       - apiVersion: v2
 *         name: <chart-name>
 *         version: 1.2.3
 *         appVersion: 4.5.6
 *         description: ...
 *         icon: https://...
 *         urls: ["https://.../<chart>-1.2.3.tgz"]
 *         created: "2024-01-15T03:21:00Z"
 *
 * The first entry per chart is the latest version (helm publishes
 * indices sorted newest-first); subsequent entries are older versions
 * surfaced through the version-history view.
 *
 * Parsing failures (malformed YAML, missing entries) yield empty
 * lists — the studio renders an empty-state rather than a stack trace
 * if a misconfigured repo emits garbage.
 */
object HelmIndex {

    /**
     * Lists every chart in the repo, optionally filtered by a
     * case-insensitive substring against chart name / description.
     * One row per chart — the row carries the *latest* version.
     */
    fun charts(repoName: String, indexYaml: String?, search: String? = null): List<K8sHelmChart> {
        val entries = parseEntries(indexYaml) ?: return emptyList()
        val needle = search?.takeIf { it.isNotBlank() }?.lowercase()

        return entries.mapNotNull { (chartName, versions) ->
            val latest = versions.firstOrNull() ?: return@mapNotNull null
            val description = latest.string("description").orEmpty()
            if (needle != null &&
                needle !in chartName.lowercase() &&
                needle !in description.lowercase()) return@mapNotNull null
            K8sHelmChart(
                id = "$repoName/$chartName",
                name = chartName,
                repo = repoName,
                version = latest.string("version").orEmpty(),
                appVersion = latest.string("appVersion").orEmpty(),
                description = description,
                icon = latest.string("icon"),
            )
        }.sortedBy { it.name }
    }

    /**
     * Lists every published version of a single chart. The first
     * (index-order) version is marked `current=true`.
     */
    fun versions(indexYaml: String?, chart: String, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): List<K8sHelmChartVersion> {
        val entries = parseEntries(indexYaml) ?: return emptyList()
        val versions = entries[chart] ?: return emptyList()
        return versions.mapIndexed { idx, v ->
            K8sHelmChartVersion(
                version = v.string("version").orEmpty(),
                appVersion = v.string("appVersion").orEmpty(),
                released = v.string("created")?.let { formatRelativeTime(it, now) } ?: "-",
                current = idx == 0,
            )
        }
    }

    /**
     * Returns the URL listed in the repo's index for the given
     * `<chart, version>` tuple. Helm's index lists download URLs in
     * `urls[0]`. The studio's "Install" flow fetches this URL to read
     * `values.yaml` + `values.schema.json` from the tarball.
     */
    fun chartUrl(indexYaml: String?, chart: String, version: String): String? {
        val entries = parseEntries(indexYaml) ?: return null
        val versions = entries[chart] ?: return null
        val match = versions.firstOrNull { it.string("version") == version } ?: return null
        val urls = match["urls"] as? List<*> ?: return null
        return urls.firstOrNull() as? String
    }

    /** Total chart count for the cached index — used by the repo list view. */
    fun chartCount(indexYaml: String?): Int = parseEntries(indexYaml)?.size ?: 0

    /**
     * SnakeYAML parses anything; we narrow to the helm index shape
     * defensively, returning null when the structure doesn't match
     * rather than throwing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseEntries(indexYaml: String?): Map<String, List<Map<String, Any?>>>? {
        if (indexYaml.isNullOrBlank()) return null
        val parsed = try {
            Yaml().load<Any?>(indexYaml)
        } catch (_: Exception) {
            return null
        }
        val map = parsed as? Map<String, Any?> ?: return null
        val entries = map["entries"] as? Map<String, Any?> ?: return null
        return entries.mapValues { (_, v) ->
            (v as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }
        }
    }

    private fun Map<String, Any?>.string(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }
}
