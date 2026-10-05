package bosca.kubernetes.controller.util

import bosca.kubernetes.model.HelmStatus
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the helm release Secret decoding path.
 *
 * The key invariant this test pins is the *doubly* base64-encoded payload
 * format: helm wraps `gzip(json)` in base64, then the Kubernetes API server
 * base64-encodes the Secret data for wire transport. fabric8's typed `Secret`
 * returns the k8s-wire-encoded string verbatim — the decoder must apply two
 * base64 passes before gunzipping or the gzip header check fails and the
 * resulting wire record reads as null at every field.
 */
class HelmReleaseDecoderTest {

    @Test
    fun `isHelmReleaseSecret matches helm release type`() {
        val secret = SecretBuilder()
            .withType("helm.sh/release.v1")
            .withNewMetadata().endMetadata()
            .build()
        assertTrue(HelmReleaseDecoder.isHelmReleaseSecret(secret))
    }

    @Test
    fun `isHelmReleaseSecret matches owner=helm label as fallback`() {
        val secret = SecretBuilder()
            .withType("Opaque")
            .withMetadata(ObjectMeta().apply { labels = mapOf("owner" to "helm") })
            .build()
        assertTrue(HelmReleaseDecoder.isHelmReleaseSecret(secret))
    }

    @Test
    fun `isHelmReleaseSecret rejects unrelated secrets`() {
        val secret = SecretBuilder()
            .withType("kubernetes.io/dockerconfigjson")
            .withNewMetadata().endMetadata()
            .build()
        assertFalse(HelmReleaseDecoder.isHelmReleaseSecret(secret))
    }

    @Test
    fun `decodeRelease decodes a doubly-base64+gzip helm payload`() {
        val secret = buildReleaseSecret(
            namespace = "default",
            name = "podinfo-smoke",
            chartName = "podinfo",
            chartVersion = "6.11.2",
            appVersion = "6.11.2",
            revision = 1,
            status = "deployed",
            description = "Install complete",
        )

        val release = HelmReleaseDecoder.decodeRelease(secret)

        assertNotNull(release, "decodeRelease returned null — the double-base64 path is broken")
        assertEquals("podinfo-smoke", release.name)
        assertEquals("default", release.namespace)
        assertEquals("podinfo", release.chart)
        assertEquals("6.11.2", release.chartVersion)
        assertEquals("6.11.2", release.appVersion)
        assertEquals(1, release.revision)
        assertEquals(HelmStatus.DEPLOYED, release.status)
        assertEquals("Install complete", release.description)
    }

    @Test
    fun `decodeRelease maps pending status labels`() {
        for (raw in listOf("pending-install", "pending-upgrade", "pending-rollback")) {
            val secret = buildReleaseSecret(status = raw)
            val release = HelmReleaseDecoder.decodeRelease(secret)
            assertEquals(HelmStatus.PENDING, release?.status, "raw=$raw")
        }
    }

    @Test
    fun `decodeRelease maps failed and superseded status labels`() {
        assertEquals(
            HelmStatus.FAILED,
            HelmReleaseDecoder.decodeRelease(buildReleaseSecret(status = "failed"))?.status,
        )
        assertEquals(
            HelmStatus.SUPERSEDED,
            HelmReleaseDecoder.decodeRelease(buildReleaseSecret(status = "superseded"))?.status,
        )
        assertEquals(
            HelmStatus.UNINSTALLED,
            HelmReleaseDecoder.decodeRelease(buildReleaseSecret(status = "uninstalled"))?.status,
        )
    }

    @Test
    fun `decodeRelease returns null when the release data field is missing`() {
        val secret = SecretBuilder()
            .withType("helm.sh/release.v1")
            .withMetadata(ObjectMeta().apply {
                namespace = "default"
                name = "broken"
                labels = mapOf("owner" to "helm", "name" to "broken", "version" to "1", "status" to "deployed")
            })
            // Intentionally no `data.release` — simulating a malformed/empty Secret.
            .build()
        assertNull(HelmReleaseDecoder.decodeRelease(secret))
    }

    @Test
    fun `decodeRevision returns the revision shape for a release Secret`() {
        // The history view uses decodeRevision rather than decodeRelease —
        // same doubly-base64 + gzip payload, lighter projection (no repo
        // back-link, just revision / status / chart / appVersion).
        val secret = buildReleaseSecret(
            revision = 3,
            status = "superseded",
            chartName = "podinfo",
            appVersion = "6.5.0",
            description = "Upgrade complete",
        )
        val revision = HelmReleaseDecoder.decodeRevision(secret)
        assertNotNull(revision)
        assertEquals(3, revision.revision)
        assertEquals(HelmStatus.SUPERSEDED, revision.status)
        assertEquals("podinfo", revision.chart)
        assertEquals("6.5.0", revision.appVersion)
        assertEquals("Upgrade complete", revision.description)
    }

    @Test
    fun `decodeRevision returns null when the release Secret has no version`() {
        // No `version` label and the helm JSON also lacks one — the function
        // can't position the revision in history, so it returns null rather
        // than emitting a record with revision=0.
        val secret = SecretBuilder()
            .withType("helm.sh/release.v1")
            .withMetadata(io.fabric8.kubernetes.api.model.ObjectMeta().apply {
                namespace = "default"
                name = "no-version"
                labels = mapOf("owner" to "helm", "name" to "no-version")
            })
            .build()
        assertNull(HelmReleaseDecoder.decodeRevision(secret))
    }

    @Test
    fun `decodeRelease falls back to chart name from release JSON when label is missing`() {
        // Secrets older than helm 3 sometimes lack the `name` label; the decoder
        // should still extract the release name from the JSON payload itself.
        val secret = buildReleaseSecret(
            name = "fallback-name",
            includeNameLabel = false,
        )
        val release = HelmReleaseDecoder.decodeRelease(secret)
        assertNotNull(release)
        assertEquals("fallback-name", release.name)
    }

    private fun labelsFor(name: String, revision: Int, status: String, includeNameLabel: Boolean): Map<String, String> {
        val labels = mutableMapOf<String, String>()
        labels["owner"] = "helm"
        if (includeNameLabel) labels["name"] = name
        labels["version"] = revision.toString()
        labels["status"] = status
        return labels
    }

    /**
     * Builds a helm-shaped release Secret. The payload is doubly base64-encoded
     * (`base64(base64(gzip(json)))`) to match what fabric8's typed Secret
     * returns when the Kubernetes API server hands back a release Secret —
     * the same condition the decoder fix from this commit set out to handle.
     */
    private fun buildReleaseSecret(
        namespace: String = "default",
        name: String = "podinfo-smoke",
        chartName: String = "podinfo",
        chartVersion: String = "6.11.2",
        appVersion: String = "6.11.2",
        revision: Int = 1,
        status: String = "deployed",
        description: String = "Install complete",
        includeNameLabel: Boolean = true,
    ): Secret {
        val json = """{
            "name":"$name",
            "version":$revision,
            "info":{
                "first_deployed":"2026-05-15T12:00:00Z",
                "last_deployed":"2026-05-15T12:00:00Z",
                "status":"$status",
                "description":"$description"
            },
            "chart":{
                "metadata":{
                    "name":"$chartName",
                    "version":"$chartVersion",
                    "appVersion":"$appVersion"
                }
            }
        }""".trimIndent()

        val gzipBytes = ByteArrayOutputStream().also { baos ->
            GZIPOutputStream(baos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val helmEncoded = Base64.getEncoder().encodeToString(gzipBytes)
        val k8sEncoded = Base64.getEncoder().encodeToString(helmEncoded.toByteArray(Charsets.UTF_8))

        return SecretBuilder()
            .withType("helm.sh/release.v1")
            .withMetadata(ObjectMeta().apply {
                this.namespace = namespace
                this.name = "sh.helm.release.v1.$name.v$revision"
                uid = "test-uid-$name-$revision"
                labels = labelsFor(name, revision, status, includeNameLabel)
            })
            .withData<String, String>(java.util.Collections.singletonMap("release", k8sEncoded))
            .build()
    }
}
