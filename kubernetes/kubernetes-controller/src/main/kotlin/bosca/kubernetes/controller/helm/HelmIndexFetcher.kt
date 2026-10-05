package bosca.kubernetes.controller.helm

import bosca.kubernetes.controller.util.formatRelativeTime
import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.model.K8sHelmChartValues
import bosca.kubernetes.model.K8sHelmRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * Fetches helm `index.yaml` and chart tarballs over plain HTTP.
 *
 * OCI repositories (`oci://`) need a registry-style protocol that's
 * out of scope for v1 — they're rejected at the repo-add step. v1
 * supports plain HTTPS repositories which serve `index.yaml` from
 * the repo's root URL.
 *
 * The fetcher also reads `values.yaml` and (optionally)
 * `values.schema.json` out of a chart tarball, which the studio uses
 * to seed the install wizard's editor.
 */
class HelmIndexFetcher(
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * GETs `<repoUrl>/index.yaml`. Returns the raw YAML body so it
     * can be persisted verbatim — re-parsing is the read-path's job.
     * Throws on non-2xx; callers map that to a wire error.
     */
    suspend fun fetchIndex(
        repoUrl: String,
        credentials: HelmRepoCredentials? = null,
    ): String = withContext(Dispatchers.IO) {
        val url = "${repoUrl.trimEnd('/')}/index.yaml".toHttpUrl()
        val request = request(url, credentials, url)
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("helm index fetch returned ${response.code} for $url")
            }
            response.body.string()
        }
    }

    /**
     * Downloads the chart tarball at [tgzUrl] and extracts
     * `values.yaml` + `values.schema.json` from inside `<chart>/`
     * (the tarball's single top-level directory).
     *
     * Returns empty default-values if `values.yaml` is missing —
     * a few charts deliberately ship without one. A missing
     * `values.schema.json` is normal; the studio renders the
     * editor without schema-aware hints in that case.
     */
    suspend fun fetchValues(
        tgzUrl: String,
        repoUrl: String = tgzUrl,
        credentials: HelmRepoCredentials? = null,
    ): K8sHelmChartValues = withContext(Dispatchers.IO) {
        val repoBase = "${repoUrl.trimEnd('/')}/".toHttpUrl()
        val url = tgzUrl.toHttpUrlOrNull()
            ?: repoBase.resolve(tgzUrl)
            ?: error("invalid helm chart URL $tgzUrl")
        val request = request(url, credentials, repoBase)
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("helm chart fetch returned ${response.code} for $url")
            }
            val bytes = response.body.bytes()
            extractValues(bytes)
        }
    }

    /**
     * Stitches a [K8sHelmRepo] view together from a stored row + the
     * cached index. `charts` is the count of distinct chart entries,
     * `lastUpdate` is the relative-time render of `last_index_at`.
     */
    fun toRepoView(
        name: String,
        url: String,
        type: String,
        indexYaml: String?,
        lastIndexAt: OffsetDateTime?,
        now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ): K8sHelmRepo = K8sHelmRepo(
        name = name,
        url = url,
        type = type,
        charts = HelmIndex.chartCount(indexYaml),
        lastUpdate = lastIndexAt?.let { formatRelativeTime(it.toString(), now) } ?: "-",
    )

    private fun request(
        url: HttpUrl,
        credentials: HelmRepoCredentials?,
        credentialScope: HttpUrl,
    ): Request {
        val builder = Request.Builder().url(url).get()
        if (credentials != null && url.sameOrigin(credentialScope)) {
            builder.header("Authorization", Credentials.basic(credentials.username, credentials.password))
        }
        return builder.build()
    }

    /**
     * Walks the tarball entries looking for `<dir>/values.yaml` and
     * `<dir>/values.schema.json`. We don't assume the top-level
     * directory's name matches the chart — helm publishes charts
     * with a `<chart-name>/` prefix but we tolerate variations
     * defensively.
     */
    private fun extractValues(bytes: ByteArray): K8sHelmChartValues {
        var defaultValues = ""
        var schema: JsonElement? = null
        TarArchiveInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && (name.endsWith("/values.yaml") || name == "values.yaml")) {
                    defaultValues = tar.readBytes().toString(StandardCharsets.UTF_8)
                }
                if (!entry.isDirectory && (name.endsWith("/values.schema.json") || name == "values.schema.json")) {
                    val raw = tar.readBytes().toString(StandardCharsets.UTF_8)
                    schema = runCatching { json.parseToJsonElement(raw) }.getOrNull()
                }
                entry = tar.nextEntry
            }
        }
        return K8sHelmChartValues(defaultValues = defaultValues, schema = schema)
    }
}

private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
