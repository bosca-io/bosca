package bosca.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PostgresBackupManifestTest {
    private val manifest = PostgresBackupManifest(
        "capture", "source", "bosca", "2026-10-18T00:00:00Z", "2026-10-18T00:01:00Z",
        "00000003-0000001B-1", "100:104:101", "18.1", 180001, "UTF8", mapOf("vector" to "0.8.1"),
        128, "a".repeat(64),
    )

    @Test
    fun `content inventory records its byte identity and rejects unsupported descriptors`() {
        val inventory = PostgresBackupContentInventory(0, 2, "b".repeat(64))
        val selected = manifest.copy(contentInventory = inventory)
        val encoded = Json.encodeToString(PostgresBackupManifest.serializer(), selected)
        assertEquals(selected, Json.decodeFromString(PostgresBackupManifest.serializer(), encoded))
        selected.validate()
        for (invalid in listOf(
            inventory.copy(count = -1), inventory.copy(length = -1), inventory.copy(length = 1),
            inventory.copy(sha256 = "etag"), inventory.copy(path = "../outside"),
            inventory.copy(path = "database.dump"), inventory.copy(version = 2),
        )) {
            assertFailsWith<IllegalArgumentException> { manifest.copy(contentInventory = invalid).validate() }
        }
    }

    @Test
    fun `native database provenance round trips with required headers`() {
        val encoded = Json.encodeToString(PostgresBackupManifest.serializer(), manifest)
        val decoded = Json.decodeFromString(PostgresBackupManifest.serializer(), encoded)
        decoded.validate()
        assertEquals(manifest, decoded)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        for (header in listOf("format", "version", "payloadPath", "requiredKeys")) {
            assertFailsWith<SerializationException> {
                Json.decodeFromJsonElement(PostgresBackupManifest.serializer(), JsonObject(fields - header))
            }
        }
    }

    @Test
    fun `native manifest explicitly records an empty key inventory`() {
        val encoded = Json.encodeToJsonElement(PostgresBackupManifest.serializer(), manifest).jsonObject
        assertEquals(JsonObject(emptyMap()), assertNotNull(encoded["requiredKeys"]))
    }

    @Test
    fun `key requirements preserve names and sources and reject incomplete declarations`() {
        val selected = manifest.copy(requiredKeys = mapOf(
            "security.encryption.key" to listOf("public.configuration_values"),
            "PIPELINE_SECRET_KEY" to listOf("pipelines.pipeline_secret", "git.pipeline_secrets"),
        ))
        selected.validate()
        assertEquals(selected, Json.decodeFromString(PostgresBackupManifest.serializer(),
            Json.encodeToString(PostgresBackupManifest.serializer(), selected)))
        for (invalid in listOf(
            mapOf(" " to listOf("public.configuration_values")),
            mapOf("security.encryption.key" to emptyList()),
            mapOf("security.encryption.key" to listOf(" ")),
            mapOf("security.encryption.key" to listOf("public.configuration_values", "public.configuration_values")),
        )) {
            assertFailsWith<IllegalArgumentException> { manifest.copy(requiredKeys = invalid).validate() }
        }
    }

    @Test
    fun `native manifests reject the version without required key metadata`() {
        assertFailsWith<IllegalArgumentException> { manifest.copy(version = 1).validate() }
    }

    @Test
    fun `format version and native payload name are fixed`() {
        for (invalid in listOf(manifest.copy(format = "other"), manifest.copy(version = 3),
            manifest.copy(payloadPath = "../data"), manifest.copy(payloadPath = "other.dump"))) {
            assertFailsWith<IllegalArgumentException> { invalid.validate() }
        }
    }

    @Test
    fun `database and capture identities cannot be blank`() {
        for (invalid in listOf(manifest.copy(id = ""), manifest.copy(sourceId = " "), manifest.copy(database = ""),
            manifest.copy(snapshotId = ""), manifest.copy(snapshot = ""), manifest.copy(serverVersion = ""),
            manifest.copy(serverVersionNumber = 0), manifest.copy(encoding = ""))) {
            assertFailsWith<IllegalArgumentException> { invalid.validate() }
        }
    }

    @Test
    fun `boundary and completion are ordered instants`() {
        for (invalid in listOf(manifest.copy(created = "yesterday"), manifest.copy(completed = "later"),
            manifest.copy(completed = "2026-10-17T00:00:00Z"))) {
            assertFailsWith<IllegalArgumentException> { invalid.validate() }
        }
    }

    @Test
    fun `native dumps require positive length and lowercase sha256`() {
        for (invalid in listOf(manifest.copy(length = 0), manifest.copy(length = -1),
            manifest.copy(sha256 = "etag"), manifest.copy(sha256 = "G".repeat(64)), manifest.copy(sha256 = "A".repeat(64)))) {
            assertFailsWith<IllegalArgumentException> { invalid.validate() }
        }
    }

    @Test
    fun `installed extensions need names and versions`() {
        for (extensions in listOf(mapOf("" to "1"), mapOf("vector" to " "))) {
            assertFailsWith<IllegalArgumentException> { manifest.copy(extensions = extensions).validate() }
        }
        manifest.copy(extensions = emptyMap()).validate()
    }
}
