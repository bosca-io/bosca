package bosca.backup

import bosca.server.config.ApplicationConfig
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PostgresBackupKeyRequirementsTest {
    private val manifest = PostgresBackupManifest(
        "capture", "source", "bosca", "2026-10-18T00:00:00Z", "2026-10-18T00:01:00Z",
        "00000003-0000001B-1", "100:104:101", "18.1", 180001, "UTF8", emptyMap(), 128, "a".repeat(64),
    )

    private fun declared(vararg keys: String) = manifest.copy(
        requiredKeys = keys.associateWith { listOf("public.encrypted_values") },
    )

    private fun config(key: String? = null): ApplicationConfig {
        val value = buildJsonObject {
            key?.let { put("security", buildJsonObject {
                put("encryption", buildJsonObject { put("key", it) })
            }) }
        }
        return ApplicationConfig.load(value.toString().byteInputStream())
    }

    @Test
    fun `undeclared keys need no configuration or environment reads`() {
        checkPostgresBackupKeys(manifest, config()) { error("No environment lookup is needed") }
    }

    @Test
    fun `security key must be configured and retains the existing opaque value contract`() {
        val selected = declared("security.encryption.key")
        val missing = assertFailsWith<IllegalStateException> {
            checkPostgresBackupKeys(selected, config()) { error("Security encryption does not use this lookup") }
        }
        assertTrue(missing.message.orEmpty().contains("security.encryption.key"))
        for (key in listOf("", " ", "opaque-unencoded-key")) {
            checkPostgresBackupKeys(selected, config(key)) { error("Security encryption does not use this lookup") }
        }
    }

    @Test
    fun `pipeline key accepts all supported AES lengths without requiring the security key`() {
        for (length in listOf(16, 24, 32)) {
            val requests = mutableListOf<String>()
            checkPostgresBackupKeys(declared("PIPELINE_SECRET_KEY"), config()) { name ->
                requests.add(name)
                Base64.getEncoder().encodeToString(ByteArray(length) { 7 })
            }
            assertEquals(listOf("PIPELINE_SECRET_KEY"), requests)
        }
    }

    @Test
    fun `pipeline key requires an explicit environment value for retained secrets`() {
        val failure = assertFailsWith<IllegalStateException> {
            checkPostgresBackupKeys(declared("PIPELINE_SECRET_KEY"), config("available")) { null }
        }
        assertTrue(failure.message.orEmpty().contains("PIPELINE_SECRET_KEY"))
    }

    @Test
    fun `pipeline key rejects malformed encoding and lengths without reporting key values`() {
        val candidates = listOf("private-invalid-key~") + listOf(0, 1, 15, 17, 23, 25, 31, 33).map {
            Base64.getEncoder().encodeToString(ByteArray(it) { 7 })
        }
        for (candidate in candidates) {
            val failure = assertFailsWith<IllegalStateException> {
                checkPostgresBackupKeys(declared("PIPELINE_SECRET_KEY"), config()) { candidate }
            }
            assertTrue(failure.message.orEmpty().contains("PIPELINE_SECRET_KEY"))
            if (candidate.isNotEmpty()) assertFalse(failure.stackTraceToString().contains(candidate))
        }
    }

    @Test
    fun `every declared requirement must be supplied`() {
        val selected = declared("security.encryption.key", "PIPELINE_SECRET_KEY")
        assertFailsWith<IllegalStateException> {
            checkPostgresBackupKeys(selected, config()) { Base64.getEncoder().encodeToString(ByteArray(32)) }
        }
        assertFailsWith<IllegalStateException> { checkPostgresBackupKeys(selected, config("available")) { null } }
        checkPostgresBackupKeys(selected, config("available")) { Base64.getEncoder().encodeToString(ByteArray(32)) }
    }

    @Test
    fun `unsupported requirements fail before reading available keys`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            checkPostgresBackupKeys(declared("plugin.encryption.key", "PIPELINE_SECRET_KEY"), config("available")) {
                error("Unknown requirements must fail first")
            }
        }
        assertTrue(failure.message.orEmpty().contains("plugin.encryption.key"))
    }

    @Test
    fun `environment lookup cancellation is preserved`() {
        val cancelled = CancellationException("lookup cancelled")
        val actual = assertFailsWith<CancellationException> {
            checkPostgresBackupKeys(declared("PIPELINE_SECRET_KEY"), config()) { throw cancelled }
        }
        assertSame(cancelled, actual)
    }
}
