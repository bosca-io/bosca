package bosca.kubernetes.controller.util

import bosca.kubernetes.model.HelmStatus
import bosca.kubernetes.model.K8sHelmRelease
import bosca.kubernetes.model.K8sHelmRevision
import io.fabric8.kubernetes.api.model.Secret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Decoder for helm 3 release Secrets.
 *
 * Helm 3 stores each release revision as a `Secret` of type
 * `helm.sh/release.v1` in the target namespace, with label
 * `owner=helm`. The secret's `data["release"]` field is **doubly**
 * base64-encoded: kubernetes wraps every Secret value in base64, and
 * helm wraps its serialized release in base64 + gzip before storing.
 * After both decoding passes the payload is the helm release JSON.
 *
 * This decoder hides those details from the route layer — callers
 * see a typed [K8sHelmRelease] / [K8sHelmRevision].
 */
object HelmReleaseDecoder {

    private val log = LoggerFactory.getLogger(HelmReleaseDecoder::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns the helm release labels for a Secret if it is a helm
     * release record, null otherwise. Helm uses `owner=helm`
     * exclusively for its release Secrets.
     */
    fun isHelmReleaseSecret(secret: Secret): Boolean =
        secret.type == "helm.sh/release.v1" || secret.metadata?.labels?.get("owner") == "helm"

    /**
     * Decodes a release Secret into the wire [K8sHelmRelease] shape.
     * Returns null if the Secret doesn't carry a valid helm payload —
     * the caller should skip the secret rather than abort the list.
     */
    fun decodeRelease(
        secret: Secret,
        repoByChartUrl: Map<String, RepoRef> = emptyMap(),
        now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ): K8sHelmRelease? {
        val release = decodeReleaseJson(secret) ?: return null
        val labels = secret.metadata?.labels.orEmpty()
        val name = labels["name"] ?: release.asObject("name")?.toContentOrNull() ?: return null
        val namespace = secret.metadata?.namespace.orEmpty()
        val revision = labels["version"]?.toIntOrNull()
            ?: release.asObject("version")?.toIntOrNull()
            ?: 0
        val statusLabel = labels["status"] ?: release.asObject("info")?.jsonObject?.get("status")?.toContentOrNull()
        val status = mapStatus(statusLabel)
        val chartObj = release.asObject("chart")?.jsonObject
        val metadata = chartObj?.get("metadata")?.jsonObject
        val chartName = metadata?.get("name")?.toContentOrNull().orEmpty()
        val chartVersion = metadata?.get("version")?.toContentOrNull().orEmpty()
        val appVersion = metadata?.get("appVersion")?.toContentOrNull().orEmpty()
        val info = release.asObject("info")?.jsonObject
        val firstDeployed = info?.get("first_deployed")?.toContentOrNull()
        val lastDeployed = info?.get("last_deployed")?.toContentOrNull()
        val description = info?.get("description")?.toContentOrNull().orEmpty()

        // Try to back-link to a configured repo: helm doesn't store the
        // source-repo on a release, but we can fingerprint by chart
        // download URL (`urls[0]`) which the index publisher includes
        // verbatim in index.yaml. When that's not present, fall back
        // to source labels (`io.helm.sh/chart-repo`) some operators
        // set, otherwise empty.
        val chartUrl = (chartObj?.get("metadata")?.jsonObject?.get("source") as? JsonElement)?.toContentOrNull()
        val repoRef = chartUrl?.let { repoByChartUrl[it] }
            ?: RepoRef("", "")

        return K8sHelmRelease(
            id = secret.metadata?.uid ?: "$namespace/$name/$revision",
            name = name,
            namespace = namespace,
            chart = chartName,
            chartVersion = chartVersion,
            appVersion = appVersion,
            revision = revision,
            status = status,
            updated = formatRelativeTime(lastDeployed, now),
            installed = formatRelativeTime(firstDeployed, now),
            repo = repoRef.name,
            repoUrl = repoRef.url,
            description = description,
        )
    }

    /**
     * Decodes a Secret into a single [K8sHelmRevision] suitable for
     * the history list view. Lighter than `decodeRelease` — no repo
     * resolution because revisions are scoped to their parent
     * release on the studio side.
     */
    fun decodeRevision(secret: Secret, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): K8sHelmRevision? {
        val release = decodeReleaseJson(secret) ?: return null
        val labels = secret.metadata?.labels.orEmpty()
        val revision = labels["version"]?.toIntOrNull()
            ?: release.asObject("version")?.toIntOrNull()
            ?: return null
        val info = release.asObject("info")?.jsonObject
        val lastDeployed = info?.get("last_deployed")?.toContentOrNull()
        val statusLabel = labels["status"] ?: info?.get("status")?.toContentOrNull()
        val chartObj = release.asObject("chart")?.jsonObject?.get("metadata")?.jsonObject
        return K8sHelmRevision(
            revision = revision,
            updated = formatRelativeTime(lastDeployed, now),
            status = mapStatus(statusLabel),
            chart = chartObj?.get("name")?.toContentOrNull().orEmpty(),
            appVersion = chartObj?.get("appVersion")?.toContentOrNull().orEmpty(),
            description = info?.get("description")?.toContentOrNull().orEmpty(),
        )
    }

    /**
     * Reads `data["release"]`, base64-decodes (kubernetes), base64-
     * decodes again (helm), ungzips, parses as JSON. Returns null on
     * any decoding step failure so the caller can skip a malformed
     * secret without blowing up the list.
     */
    private fun decodeReleaseJson(secret: Secret): JsonObject? {
        val outer = secret.data?.get("release") ?: return null
        return try {
            // helm release Secrets are doubly base64-encoded: helm
            // wraps `gzip(json)` in base64, then the kubernetes API
            // server wraps the whole Secret value in another base64
            // layer for wire transport. fabric8's typed Secret returns
            // `data` values as the raw k8s-wire base64 string (NOT
            // decoded), so we need two passes before gunzipping.
            val k8sDecoded = Base64.getDecoder().decode(outer)
            val helmDecoded = Base64.getDecoder().decode(k8sDecoded)
            val plain = GZIPInputStream(helmDecoded.inputStream()).readBytes()
            json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            log.debug("failed decoding helm release secret {}/{}: {}",
                secret.metadata?.namespace, secret.metadata?.name, e.message)
            null
        }
    }

    private fun JsonObject.asObject(key: String): JsonElement? = this[key]

    private fun JsonElement.toContentOrNull(): String? =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private fun JsonElement.toIntOrNull(): Int? =
        runCatching { jsonPrimitive.intOrNull }.getOrNull()

    private fun mapStatus(raw: String?): HelmStatus = when (raw?.lowercase()) {
        "deployed" -> HelmStatus.DEPLOYED
        "pending-install", "pending-upgrade", "pending-rollback" -> HelmStatus.PENDING
        "failed" -> HelmStatus.FAILED
        "superseded" -> HelmStatus.SUPERSEDED
        "uninstalled" -> HelmStatus.UNINSTALLED
        else -> HelmStatus.PENDING
    }

    /** Convenience for parsing a release secret's helm-recorded timestamps. */
    @Suppress("unused")
    fun parseTimestamp(s: String?): OffsetDateTime? {
        if (s.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(s)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Source-repo back-link used by [decodeRelease]. Kept as a small
     * named record so future enrichment (e.g. registry credentials)
     * lives in one place.
     */
    data class RepoRef(val name: String, val url: String)
}
