package bosca.backup.graphql

import bosca.backup.ObjectBackupStorageService
import bosca.backup.PostgresBackupManifest
import bosca.backup.PostgresBackupReceipt
import bosca.backup.PostgresBackupRunRepository
import bosca.db.transaction
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.storage.configuration.StorageConfiguration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real schema, dispatchers, authorization and native backup journal mapping. */
@OptIn(InternalDI::class)
class PostgresBackupRunGraphQLIntegrationTest : ObjectBackupGraphQLTest() {
    private suspend fun prepare(key: String = "native") = request {
        provide<ObjectBackupStorageService>().bind("native-store",
            StorageConfiguration("fs", "", basePath = directory.resolve("native").toString()))
        provide<PostgresBackupRunRepository>().add(key, "source", "native-store", "postgresql")
    }

    private suspend fun running() {
        prepare()
        request { transaction {
            provide<PostgresBackupRunRepository>().begin("native", "observed-token")
            provide<PostgresBackupRunRepository>().claim("native", "native-store")
        } }
    }

    @Test
    fun `native query resolves mapped fields enum status nulls and offset pagination`(): Unit = runBlocking {
        prepare()
        prepare("later")
        val response = execute("""{ backups {
            postgresRun(key: "native") { key sourceId storageKey prefix status attempts writerToken error result { key } version created modified }
            postgresRuns(sourceId: "source", storageKey: "native-store", offset: 1, limit: 1) { key }
        } }""")
        val data = nativeData(response)
        val value = data.getValue("postgresRun").jsonObject
        assertEquals("native", value.string("key"))
        assertEquals("source", value.string("sourceId"))
        assertEquals("native-store", value.string("storageKey"))
        assertEquals("PENDING", value.string("status"))
        assertEquals("0", value.string("attempts"))
        assertEquals(JsonNull, value.getValue("writerToken"))
        assertEquals(JsonNull, value.getValue("error"))
        assertEquals(JsonNull, value.getValue("result"))
        assertEquals("native", data.getValue("postgresRuns").jsonArray.single().jsonObject.string("key"))
    }

    @Test
    fun `native recovery requires administration and the exact stopped writer token`(): Unit = runBlocking {
        running()
        val mutation = """mutation { backups { recoverPostgresRun(key: "native", expectedToken: "observed-token") { key status writerToken error } } }"""
        assertTrue("errors" in execute(mutation, anonymous))
        assertEquals("observed-token", request { provide<PostgresBackupRunRepository>().get("native") }?.writerToken)
        assertTrue("errors" in execute(mutation.replace("observed-token", "stale-token")))
        assertEquals("observed-token", request { provide<PostgresBackupRunRepository>().get("native") }?.writerToken)
        val value = nativeData(execute(mutation)).getValue("recoverPostgresRun").jsonObject
        assertEquals("FAILED", value.string("status"))
        assertEquals(JsonNull, value.getValue("writerToken"))
        assertTrue(value.string("error").contains("Stopped"))
    }

    @Test
    fun `completed native receipt exposes the exact manifest and retained location`(): Unit = runBlocking {
        running()
        val manifest = PostgresBackupManifest("artifact", "source", "database", "2026-10-19T00:00:00Z", "2026-10-19T00:00:01Z",
            "exported", "1:2:", "18", 180000, "UTF8", mapOf("vector" to "0.8.0"), 5, "a".repeat(64))
        val receipt = PostgresBackupReceipt("native", "native-store", "postgresql/native/artifact", manifest)
        request { transaction {
            provide<PostgresBackupRunRepository>().complete("native", "observed-token", receipt)
            provide<PostgresBackupRunRepository>().release("native")
        } }
        val value = nativeData(execute("""{ backups { postgresRun(key: "native") { status result {
            key storageKey path manifest { id sourceId database created completed snapshotId snapshot serverVersion serverVersionNumber encoding extensions length sha256 payloadPath version format }
        } } } }""")).getValue("postgresRun").jsonObject
        assertEquals("COMPLETED", value.string("status"))
        val result = value.getValue("result").jsonObject
        assertEquals(receipt.path, result.string("path"))
        assertEquals(receipt.storageKey, result.string("storageKey"))
        val actual = result.getValue("manifest").jsonObject
        assertEquals(manifest.id, actual.string("id"))
        assertEquals(manifest.sha256, actual.string("sha256"))
        assertEquals("0.8.0", actual.getValue("extensions").jsonObject.string("vector"))
        assertEquals("5", actual.string("length"))
    }

    @Test
    fun `native administration rejects unauthorized reads and invalid pages`(): Unit = runBlocking {
        prepare()
        val query = """{ backups { postgresRuns(sourceId: "source", storageKey: "native-store") { key } } }"""
        val denied = execute(query, anonymous)
        assertTrue("errors" in denied)
        assertFalse(denied.toString().contains("observed-token"))
        assertTrue("errors" in execute(query.replace("storageKey: \"native-store\"", "storageKey: \"native-store\", offset: -1")))
        assertEquals(JsonNull, nativeData(execute("""{ backups { postgresRun(key: "missing") { key } } }""")).getValue("postgresRun"))
    }

    private fun nativeData(response: JsonObject): JsonObject {
        assertFalse("errors" in response, response.toString())
        return response.getValue("data").jsonObject.getValue("backups").jsonObject
    }
}
