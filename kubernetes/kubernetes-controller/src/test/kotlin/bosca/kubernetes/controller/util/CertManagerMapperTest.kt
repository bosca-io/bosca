package bosca.kubernetes.controller.util

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the cert-manager mappers. The Certificate / Issuer CRDs aren't
 * typed in fabric8 so the mappers reach into [GenericKubernetesResource.additionalProperties]
 * with explicit casts. The decisions worth fixing in tests:
 *
 *   * Status ladder for Certificate: `Ready=True → Ready`,
 *     `Issuing=True → Renewing`, `Ready=False → Failed`, otherwise `Pending`.
 *   * `expiresInDays` and `renewsInDays` are signed integer differences
 *     in days against `now`. Renewal time defaults to `-1` (sentinel)
 *     when missing.
 *   * `issuer` renders as `<kind>/<name>` with `Issuer` as the default
 *     when kind is missing.
 *   * `error` is surfaced only when Ready=False, using the Ready or
 *     Issuing condition's `message`.
 *   * Issuer `type` discriminator walks `acme → ca → vault → selfSigned`
 *     in that order; `server` is populated for ACME and Vault.
 *   * Issuer `certs` count looks up first by `<ns>/<name>` (namespaced
 *     Issuer), then by `<name>` alone (ClusterIssuer fallback).
 */
class CertManagerMapperTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-15T12:00:00Z")

    private fun cert(props: Map<String, Any?>, metadata: io.fabric8.kubernetes.api.model.ObjectMeta? = null): GenericKubernetesResource {
        val r = GenericKubernetesResourceBuilder().build()
        r.metadata = metadata ?: ObjectMetaBuilder().withName("c").withNamespace("ns").build()
        r.kind = "Certificate"
        r.apiVersion = "cert-manager.io/v1"
        props.forEach { (k, v) -> r.setAdditionalProperty(k, v) }
        return r
    }

    private fun issuer(props: Map<String, Any?>, kindStr: String = "Issuer", namespaced: Boolean = true): GenericKubernetesResource {
        val r = GenericKubernetesResourceBuilder().build()
        r.metadata = if (namespaced) {
            ObjectMetaBuilder().withName("iss").withNamespace("ns").withUid("u").build()
        } else {
            ObjectMetaBuilder().withName("iss").withUid("u").build()
        }
        r.kind = kindStr
        r.apiVersion = "cert-manager.io/v1"
        props.forEach { (k, v) -> r.setAdditionalProperty(k, v) }
        return r
    }

    // ===== Certificate status ladder =====

    @Test
    fun `ready=True maps to Ready status`() {
        val r = cert(
            mapOf(
                "spec" to mapOf("dnsNames" to listOf("api.example.com"), "secretName" to "tls"),
                "status" to mapOf(
                    "conditions" to listOf(mapOf("type" to "Ready", "status" to "True", "lastTransitionTime" to "2026-05-14T12:00:00Z")),
                    "notAfter" to "2026-08-13T12:00:00Z",
                    "renewalTime" to "2026-07-14T12:00:00Z",
                ),
            )
        )
        val w = r.toCertificate(now)
        assertEquals("Ready", w.status)
        assertEquals(listOf("api.example.com"), w.dns)
        assertEquals("tls", w.secretName)
        assertEquals("1d ago", w.readySince)
        assertEquals(90, w.expiresInDays)
        assertEquals(60, w.renewsInDays)
        assertNull(w.error)
    }

    @Test
    fun `issuing=True with no Ready=True maps to Renewing`() {
        val r = cert(
            mapOf(
                "spec" to mapOf("dnsNames" to listOf("api.example.com"), "secretName" to "tls"),
                "status" to mapOf(
                    "conditions" to listOf(
                        mapOf("type" to "Ready", "status" to "Unknown"),
                        mapOf("type" to "Issuing", "status" to "True"),
                    ),
                ),
            )
        )
        assertEquals("Renewing", r.toCertificate(now).status)
    }

    @Test
    fun `ready=False maps to Failed and surfaces the message as error`() {
        val r = cert(
            mapOf(
                "spec" to mapOf("dnsNames" to listOf("api.example.com")),
                "status" to mapOf(
                    "conditions" to listOf(
                        mapOf("type" to "Ready", "status" to "False", "message" to "DNS challenge failed"),
                    ),
                ),
            )
        )
        val w = r.toCertificate(now)
        assertEquals("Failed", w.status)
        assertEquals("DNS challenge failed", w.error)
    }

    @Test
    fun `ready=False falls back to Issuing condition message when Ready has none`() {
        val r = cert(
            mapOf(
                "status" to mapOf(
                    "conditions" to listOf(
                        mapOf("type" to "Ready", "status" to "False"),
                        mapOf("type" to "Issuing", "status" to "False", "message" to "DNS01 self-check failed"),
                    ),
                ),
            )
        )
        assertEquals("DNS01 self-check failed", r.toCertificate(now).error)
    }

    @Test
    fun `no conditions yields Pending status`() {
        val r = cert(mapOf("spec" to mapOf("dnsNames" to listOf("a"))))
        assertEquals("Pending", r.toCertificate(now).status)
    }

    // ===== Certificate issuer rendering =====

    @Test
    fun `issuer renders as kind slash name`() {
        val r = cert(
            mapOf("spec" to mapOf(
                "issuerRef" to mapOf("name" to "letsencrypt", "kind" to "ClusterIssuer"),
            ))
        )
        assertEquals("ClusterIssuer/letsencrypt", r.toCertificate(now).issuer)
    }

    @Test
    fun `issuer kind defaults to Issuer when missing`() {
        val r = cert(
            mapOf("spec" to mapOf("issuerRef" to mapOf("name" to "self")))
        )
        assertEquals("Issuer/self", r.toCertificate(now).issuer)
    }

    @Test
    fun `issuer renders blank when name is blank`() {
        val r = cert(
            mapOf("spec" to mapOf("issuerRef" to mapOf("kind" to "ClusterIssuer")))
        )
        assertEquals("", r.toCertificate(now).issuer)
    }

    // ===== Certificate id fallback and renewsInDays sentinel =====

    @Test
    fun `id falls back to Certificate slash ns slash name when uid missing`() {
        val r = cert(emptyMap())
        assertEquals("Certificate/ns/c", r.toCertificate(now).id)
    }

    @Test
    fun `renewsInDays is -1 sentinel when renewalTime is missing`() {
        val r = cert(
            mapOf(
                "status" to mapOf(
                    "conditions" to listOf(mapOf("type" to "Ready", "status" to "True")),
                    "notAfter" to "2026-08-13T12:00:00Z",
                ),
            )
        )
        assertEquals(-1, r.toCertificate(now).renewsInDays)
    }

    @Test
    fun `dns and secretName defaults are empty list and empty string`() {
        val r = cert(emptyMap())
        val w = r.toCertificate(now)
        assertEquals(emptyList(), w.dns)
        assertEquals("", w.secretName)
        assertEquals("-", w.readySince)
    }

    // ===== Issuer type discriminator =====

    @Test
    fun `acme issuer maps to ACME with server`() {
        val r = issuer(
            mapOf(
                "spec" to mapOf("acme" to mapOf("server" to "https://acme-v02.api.letsencrypt.org/directory")),
                "status" to mapOf("conditions" to listOf(mapOf("type" to "Ready", "status" to "True"))),
            )
        )
        val w = r.toIssuer(emptyMap())
        assertEquals("ACME", w.type)
        assertEquals("https://acme-v02.api.letsencrypt.org/directory", w.server)
        assertEquals("Ready", w.status)
    }

    @Test
    fun `ca issuer maps to CA with empty server`() {
        val r = issuer(mapOf("spec" to mapOf("ca" to mapOf("secretName" to "root"))))
        val w = r.toIssuer(emptyMap())
        assertEquals("CA", w.type)
        assertEquals("", w.server)
    }

    @Test
    fun `vault issuer maps to Vault with server`() {
        val r = issuer(mapOf("spec" to mapOf("vault" to mapOf("server" to "https://vault.internal"))))
        val w = r.toIssuer(emptyMap())
        assertEquals("Vault", w.type)
        assertEquals("https://vault.internal", w.server)
    }

    @Test
    fun `selfSigned issuer maps to SelfSigned`() {
        val r = issuer(mapOf("spec" to mapOf("selfSigned" to emptyMap<String, Any?>())))
        assertEquals("SelfSigned", r.toIssuer(emptyMap()).type)
    }

    @Test
    fun `issuer with no recognised discriminator maps to Other`() {
        val r = issuer(mapOf("spec" to mapOf("mystery" to "shape")))
        assertEquals("Other", r.toIssuer(emptyMap()).type)
    }

    @Test
    fun `issuer with no spec maps to Unknown type`() {
        val r = issuer(emptyMap())
        assertEquals("Unknown", r.toIssuer(emptyMap()).type)
    }

    // ===== Issuer status ladder =====

    @Test
    fun `issuer status defaults to Pending when no conditions present`() {
        val r = issuer(mapOf("spec" to mapOf("acme" to mapOf("server" to "u"))))
        assertEquals("Pending", r.toIssuer(emptyMap()).status)
    }

    @Test
    fun `issuer status=False maps to Failed`() {
        val r = issuer(
            mapOf(
                "spec" to mapOf("acme" to mapOf("server" to "u")),
                "status" to mapOf("conditions" to listOf(mapOf("type" to "Ready", "status" to "False"))),
            )
        )
        assertEquals("Failed", r.toIssuer(emptyMap()).status)
    }

    // ===== Issuer certs count lookup =====

    @Test
    fun `issuer certs count keyed by namespace slash name`() {
        val r = issuer(mapOf("spec" to mapOf("acme" to mapOf("server" to "x"))))
        val w = r.toIssuer(mapOf("ns/iss" to 4, "iss" to 99))
        assertEquals(4, w.certs, "namespaced key wins over name-only key")
    }

    @Test
    fun `issuer certs count falls back to name-only key for ClusterIssuer`() {
        val r = issuer(
            mapOf("spec" to mapOf("acme" to mapOf("server" to "x"))),
            kindStr = "ClusterIssuer",
            namespaced = false,
        )
        val w = r.toIssuer(mapOf("iss" to 7))
        assertEquals(7, w.certs)
    }

    @Test
    fun `issuer certs count is 0 when neither key matches`() {
        val r = issuer(mapOf("spec" to mapOf("acme" to mapOf("server" to "x"))))
        assertEquals(0, r.toIssuer(emptyMap()).certs)
    }

    @Test
    fun `issuer kind preserves the CRD kind on the wire`() {
        val cluster = issuer(
            mapOf("spec" to mapOf("ca" to mapOf("secretName" to "root"))),
            kindStr = "ClusterIssuer",
            namespaced = false,
        )
        val w = cluster.toIssuer(emptyMap())
        assertEquals("ClusterIssuer", w.kind)
        assertNull(w.namespace)
    }

    @Test
    fun `certificate expiry math is computed against now`() {
        val r = cert(
            mapOf(
                "status" to mapOf(
                    "conditions" to listOf(mapOf("type" to "Ready", "status" to "True")),
                    "notAfter" to "2026-05-30T12:00:00Z",
                    "renewalTime" to "2026-05-25T12:00:00Z",
                ),
            )
        )
        val w = r.toCertificate(now)
        assertEquals(15, w.expiresInDays)
        assertEquals(10, w.renewsInDays)
    }

    @Test
    fun `certificate handles malformed timestamps as missing without throwing`() {
        val r = cert(
            mapOf(
                "status" to mapOf(
                    "conditions" to listOf(mapOf("type" to "Ready", "status" to "True", "lastTransitionTime" to "garbage")),
                    "notAfter" to "not-a-time",
                    "renewalTime" to "also-not-a-time",
                ),
            )
        )
        val w = r.toCertificate(now)
        // readySince null parse → "-"
        assertEquals("-", w.readySince)
        assertEquals(0, w.expiresInDays)
        assertEquals(-1, w.renewsInDays)
        // status is still Ready because conditions[Ready].status == "True"
        assertEquals("Ready", w.status)
        assertNotNull(w)
    }

    @Test
    fun `certificate spec dnsNames are extracted as strings only`() {
        val r = cert(
            mapOf("spec" to mapOf(
                "dnsNames" to listOf("a.example.com", 42, "b.example.com", null),
            ))
        )
        val w = r.toCertificate(now)
        assertEquals(listOf("a.example.com", "b.example.com"), w.dns, "non-strings drop out via mapNotNull")
        assertTrue(w.dns.isNotEmpty())
    }
}
