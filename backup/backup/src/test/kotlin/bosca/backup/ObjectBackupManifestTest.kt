package bosca.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObjectBackupManifestTest {
    private val objectRevision = BackupObject(
        store = "content", key = "metadata/item/1/content", revision = "revision-1", length = 4,
        sha256 = "3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7", payloadPath = "objects/data",
    )
    private val manifest = ObjectBackupManifest("backup-1", "source-1", "content", "2026-10-15T00:00:00Z", listOf(objectRevision))

    @Test
    fun `portable inventory round trips with exact revision identity`() {
        val encoded = Json.encodeToString(ObjectBackupManifest.serializer(), manifest)
        val decoded = Json.decodeFromString(ObjectBackupManifest.serializer(), encoded)
        decoded.validate()
        assertEquals(manifest, decoded)
    }

    @Test
    fun `serialized artifacts always identify their format and version`() {
        val encoded = Json.encodeToString(ObjectBackupManifest.serializer(), manifest)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("bosca-object-backup", fields.getValue("format").jsonPrimitive.content)
        assertEquals(1, fields.getValue("version").jsonPrimitive.int)
    }

    @Test
    fun `deserialization requires explicit format and version headers`() {
        val encoded = Json.encodeToString(ObjectBackupManifest.serializer(), manifest)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        for (header in listOf("format", "version")) {
            assertFailsWith<SerializationException> {
                Json.decodeFromJsonElement(ObjectBackupManifest.serializer(), JsonObject(fields - header))
            }
        }
    }

    @Test
    fun `format and version are validated`() {
        assertFailsWith<IllegalArgumentException> { manifest.copy(format = "unknown").validate() }
        assertFailsWith<IllegalArgumentException> { manifest.copy(version = 2).validate() }
    }

    @Test
    fun `manifest identity scope and timestamp are required`() {
        for (invalid in listOf(
            manifest.copy(id = ""), manifest.copy(sourceId = " "), manifest.copy(scope = ""), manifest.copy(created = "yesterday"),
        )) assertFailsWith<IllegalArgumentException> { invalid.validate() }
    }

    @Test
    fun `object identity revision length and digest are validated`() {
        for (invalid in listOf(
            objectRevision.copy(store = ""), objectRevision.copy(key = ""), objectRevision.copy(revision = " "),
            objectRevision.copy(length = -1), objectRevision.copy(sha256 = "part-etag-2"), objectRevision.copy(sha256 = "g".repeat(64)),
        )) assertFailsWith<IllegalArgumentException> { manifest.copy(objects = listOf(invalid)).validate() }
    }

    @Test
    fun `destination payload paths are portable relative keys`() {
        for (path in listOf("", "/absolute", "objects/../data", "objects/./data", "objects//data")) {
            assertFailsWith<IllegalArgumentException> {
                manifest.copy(objects = listOf(objectRevision.copy(payloadPath = path))).validate()
            }
        }
    }

    @Test
    fun `a source revision can occur only once`() {
        assertFailsWith<IllegalArgumentException> { manifest.copy(objects = listOf(objectRevision, objectRevision)).validate() }
    }

    @Test
    fun `historical revisions and matching shared payloads are allowed`() {
        manifest.copy(objects = listOf(objectRevision, objectRevision.copy(revision = "revision-2"))).validate()
    }

    @Test
    fun `shared destination paths cannot claim different payloads`() {
        for (invalid in listOf(objectRevision.copy(key = "other", length = 5), objectRevision.copy(key = "other", sha256 = "0".repeat(64)))) {
            assertFailsWith<IllegalArgumentException> { manifest.copy(objects = listOf(objectRevision, invalid)).validate() }
        }
    }

    @Test
    fun `empty inventories are valid for an explicitly declared scope`() {
        manifest.copy(objects = emptyList()).validate()
    }
}
