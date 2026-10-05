package bosca.kubernetes.controller.util

import bosca.kubernetes.model.ConfigKind
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the ConfigMap / Secret mappers and the values-entries rendering
 * helpers. The decisions worth fixing:
 *
 *   * `keys` is `(data ∪ binaryData|stringData).sorted()` — both the
 *     listing and the detail panel sort keys alphabetically so admins
 *     can scan for known names.
 *   * `size` is a human-friendly string that decodes binary base64 into
 *     true byte counts (not the wire-base64 length).
 *   * `managedBy` heuristic resolves the standard label first; otherwise
 *     it falls back through known operator annotations; otherwise null.
 *   * Secret entries NEVER return a value: every entry keeps its key and
 *     carries the fixed `SECRET_VALUE_REDACTED` marker instead, so Secret
 *     cleartext (and its base64 wire form) never leaves the controller.
 *   * ConfigMap binaryData entries emit `base64:<wire-value>` verbatim —
 *     we never try to decode them; the consumer chooses how to render.
 */
class ConfigMapperTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    // ===== ConfigMap =====

    @Test
    fun `configmap toConfigResource sorts merged keys and reports size`() {
        val cm = ConfigMapBuilder()
            .withNewMetadata().withUid("u").withName("settings").withNamespace("prod").endMetadata()
            .addToData("zeta", "value-z")
            .addToData("alpha", "value-a")
            .addToBinaryData("binary", b64("binary-payload"))
            .build()
        val w = cm.toConfigResource()
        assertEquals("u", w.id)
        assertEquals(ConfigKind.CONFIG_MAP, w.kind)
        assertEquals("settings", w.name)
        assertEquals("prod", w.namespace)
        assertEquals(listOf("alpha", "binary", "zeta"), w.keys)
        assertNull(w.secretType, "ConfigMap has no secretType")
        // Size = "value-z" + "value-a" + "binary-payload" = 7 + 7 + 14 = 28 bytes
        assertEquals("28B", w.size)
    }

    @Test
    fun `configmap id falls back when uid missing`() {
        val cm = ConfigMapBuilder()
            .withNewMetadata().withName("settings").withNamespace("prod").endMetadata()
            .build()
        assertEquals("ConfigMap/prod/settings", cm.toConfigResource().id)
    }

    @Test
    fun `managedBy reads the standard label when present`() {
        val cm = ConfigMapBuilder()
            .withNewMetadata()
                .withName("x").withNamespace("ns")
                .addToLabels("app.kubernetes.io/managed-by", "helm")
            .endMetadata().build()
        assertEquals("helm", cm.toConfigResource().managedBy)
    }

    @Test
    fun `managedBy falls back to external-secrets annotation`() {
        val cm = ConfigMapBuilder()
            .withNewMetadata()
                .withName("x").withNamespace("ns")
                .addToAnnotations("external-secrets.io/ownership", "secret-store/foo")
            .endMetadata().build()
        assertEquals("external-secrets", cm.toConfigResource().managedBy)
    }

    @Test
    fun `managedBy falls back to cert-manager annotation`() {
        val cm = ConfigMapBuilder()
            .withNewMetadata()
                .withName("x").withNamespace("ns")
                .addToAnnotations("cert-manager.io/certificate-name", "cert")
            .endMetadata().build()
        assertEquals("cert-manager", cm.toConfigResource().managedBy)
    }

    @Test
    fun `managedBy falls back to stakater-reloader annotation`() {
        val cm = ConfigMapBuilder()
            .withNewMetadata()
                .withName("x").withNamespace("ns")
                .addToAnnotations("reloader.stakater.com/auto", "true")
            .endMetadata().build()
        assertEquals("stakater-reloader", cm.toConfigResource().managedBy)
    }

    @Test
    fun `managedBy returns null when no signal present`() {
        val cm = ConfigMapBuilder()
            .withNewMetadata().withName("x").withNamespace("ns").endMetadata()
            .build()
        assertNull(cm.toConfigResource().managedBy)
    }

    @Test
    fun `configmap toEntries emits binaryData with base64 prefix`() {
        val cm = ConfigMapBuilder()
            .withNewMetadata().withName("x").withNamespace("ns").endMetadata()
            .addToData("plain", "hello")
            .addToBinaryData("bin", "AQID")
            .build()
        val entries = cm.toEntries()
        assertEquals(2, entries.size)
        assertEquals("bin", entries[0].key)
        assertEquals("base64:AQID", entries[0].value)
        assertEquals("plain", entries[1].key)
        assertEquals("hello", entries[1].value)
    }

    // ===== Secret =====

    @Test
    fun `secret toConfigResource defaults secretType to Opaque when null`() {
        val s = SecretBuilder()
            .withNewMetadata().withName("creds").withNamespace("ns").endMetadata()
            .addToData("password", b64("hunter2"))
            .build()
        val w = s.toConfigResource()
        assertEquals(ConfigKind.SECRET, w.kind)
        assertEquals("Opaque", w.secretType)
        assertEquals(listOf("password"), w.keys)
        assertEquals("7B", w.size)  // decoded "hunter2" is 7 bytes
    }

    @Test
    fun `secret toConfigResource preserves explicit type`() {
        val s = SecretBuilder()
            .withNewMetadata().withName("tls").withNamespace("ns").endMetadata()
            .withType("kubernetes.io/tls")
            .addToData("tls.crt", b64("-----CERT-----"))
            .build()
        assertEquals("kubernetes.io/tls", s.toConfigResource().secretType)
    }

    @Test
    fun `secret toConfigResource id falls back to Secret slash ns slash name`() {
        val s = SecretBuilder()
            .withNewMetadata().withName("creds").withNamespace("ns").endMetadata()
            .build()
        assertEquals("Secret/ns/creds", s.toConfigResource().id)
    }

    @Test
    fun `secret toConfigResource size adds stringData byte counts`() {
        val s = SecretBuilder()
            .withNewMetadata().withName("x").withNamespace("ns").endMetadata()
            .addToData("a", b64("abcdef"))  // 6 bytes after decode
            .addToStringData("b", "hi")      // 2 bytes raw
            .build()
        assertEquals("8B", s.toConfigResource().size)
    }

    @Test
    fun `secret toEntries masks every value, keeping keys`() {
        val s = SecretBuilder()
            .withNewMetadata().withName("x").withNamespace("ns").endMetadata()
            .addToData("printable", b64("hello world"))
            .addToStringData("string", "raw value")
            .build()
        val entries = s.toEntries()
        assertEquals(2, entries.size)
        // Keys are metadata and stay visible, sorted.
        assertEquals(listOf("printable", "string"), entries.map { it.key })
        // Values are never returned — every one is the fixed redaction marker.
        assertTrue(entries.all { it.value == SECRET_VALUE_REDACTED })
        // Cleartext must not appear anywhere in the output.
        assertTrue(entries.none { it.value.contains("hello") || it.value.contains("raw value") })
    }

    @Test
    fun `secret toEntries masks binary values too`() {
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val s = SecretBuilder()
            .withNewMetadata().withName("x").withNamespace("ns").endMetadata()
            .addToData("blob", Base64.getEncoder().encodeToString(binary))
            .build()
        val entry = s.toEntries().single()
        assertEquals("blob", entry.key)
        assertEquals(SECRET_VALUE_REDACTED, entry.value)
    }

    @Test
    fun `secret toEntries masks empty payloads too`() {
        val s = SecretBuilder()
            .withNewMetadata().withName("x").withNamespace("ns").endMetadata()
            .addToData("empty", b64(""))
            .build()
        assertEquals(SECRET_VALUE_REDACTED, s.toEntries().single().value)
    }

    // ===== humanSize tier transitions =====

    @Test
    fun `humanSize tier transitions render bytes kB and MB at the expected breakpoints`() {
        // Build enough payload to land in each tier.
        // 1023B → "1023B"
        val justUnderKb = ConfigMapBuilder()
            .withNewMetadata().withName("x").withNamespace("n").endMetadata()
            .addToData("k", "a".repeat(1023))
            .build()
        assertEquals("1023B", justUnderKb.toConfigResource().size)

        // 1024B → "1.0kB"
        val oneKb = ConfigMapBuilder()
            .withNewMetadata().withName("x").withNamespace("n").endMetadata()
            .addToData("k", "a".repeat(1024))
            .build()
        assertTrue(oneKb.toConfigResource().size.endsWith("kB"))

        // 10 kB → "10kB" (no decimals once >= 10 kB)
        val tenKb = ConfigMapBuilder()
            .withNewMetadata().withName("x").withNamespace("n").endMetadata()
            .addToData("k", "a".repeat(10 * 1024))
            .build()
        assertEquals("10kB", tenKb.toConfigResource().size)

        // 1 MB → "1.0MB"
        val oneMb = ConfigMapBuilder()
            .withNewMetadata().withName("x").withNamespace("n").endMetadata()
            .addToData("k", "a".repeat(1024 * 1024))
            .build()
        val s = oneMb.toConfigResource().size
        assertNotNull(s)
        assertTrue(s.endsWith("MB"))
    }

    @Test
    fun `bogus base64 in secret data is treated as zero-byte payload`() {
        val s = SecretBuilder()
            .withNewMetadata().withName("x").withNamespace("ns").endMetadata()
            .addToData("bad", "!!!not-base64!!!")
            .build()
        // Size should be 0B because Base64.getDecoder().decode throws and yields ByteArray(0)
        assertEquals("0B", s.toConfigResource().size)
    }
}
