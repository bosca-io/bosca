package bosca.backup.graphql

import bosca.backup.BackupJobQueueNames
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupOperation
import bosca.backup.ObjectBackupRunStatus
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.JobQueue
import bosca.storage.configuration.StorageConfiguration
import io.mockk.confirmVerified
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Retained-manifest preparation through real generated GraphQL wiring and PostgreSQL. */
@OptIn(InternalDI::class)
class ObjectBackupConfiguredPreparationGraphQLIntegrationTest : ObjectBackupGraphQLTest() {
    private val path = "manifests/recovery.json"

    override fun applicationConfig(): ApplicationConfig {
        val configurations = mapOf(
            "retained" to StorageConfiguration("fs", "", basePath = directory.resolve("destination").toString()),
            "target" to StorageConfiguration("fs", "", basePath = directory.resolve("target").toString()),
        )
        val catalog = JsonObject(configurations.mapValues { Json.encodeToJsonElement(StorageConfiguration.serializer(), it.value) })
        return ApplicationConfig.load(buildJsonObject {
            put("backup", buildJsonObject { put("objectStores", catalog) })
        }.toString().byteInputStream())
    }

    @Test
    fun `preparation needs only retained metadata and fixes pending restore inventory and targets`(): Unit = runBlocking {
        retain()
        directory.resolve("source").toFile().deleteRecursively()
        val prepared = data(submit()).getValue("prepareRestoreConfigured").jsonObject
        val saved = assertNotNull(request { runs.get(prepared.string("key")) })

        assertEquals(inventory, saved.inventory)
        assertEquals(ObjectBackupOperation.RESTORE, saved.operation)
        assertEquals(ObjectBackupRunStatus.PENDING, saved.status)
        assertEquals("recovery", saved.destinationId)
        assertEquals(path, saved.path)
        val storage = assertNotNull(request { runs.getStorage(saved.key) })
        assertEquals("retained", storage.backupKey)
        assertEquals(mapOf("content" to "target"), storage.stores)
        assertEquals(listOf(first.key, second.key), request { runs.items(saved.key, 0, 20) }.map { it.payload.payloadPath })
        assertFalse(directory.resolve("target").toFile().exists())
        confirmVerified(provide<JobQueue>(name = BackupJobQueueNames.backupJobQueue))
    }

    @Test
    fun `replay uses the fixed request without rereading the manifest`(): Unit = runBlocking {
        retain()
        val first = data(submit())
        directory.resolve("destination").toFile().deleteRecursively()
        assertEquals(first, data(submit()))
        assertEquals(1, history().size)
    }

    @Test
    fun `changed intent cannot redirect an existing request`(): Unit = runBlocking {
        retain()
        val before = data(submit())
        for (change in listOf(
            mapOf("destinationId" to JsonPrimitive("elsewhere")),
            mapOf("backupKey" to JsonPrimitive("other")),
            mapOf("path" to JsonPrimitive("different.json")),
            mapOf("stores" to JsonArray(listOf(mapping("content", "other")))),
        )) assertError(submit(change), "Conflicting configured restore")
        assertEquals(before, data(submit()))
        assertEquals(1, history().size)
    }

    @Test
    fun `empty retained inventory prepares without target mappings`(): Unit = runBlocking {
        write(destination, path, json.encodeToString(ObjectBackupManifest.serializer(), inventory.copy(objects = emptyList())))
        val value = data(submit(mapOf("stores" to JsonArray(emptyList())))).getValue("prepareRestoreConfigured").jsonObject
        val saved = assertNotNull(request { runs.get(value.string("key")) })
        assertTrue(saved.inventory.objects.isEmpty())
        assertTrue(assertNotNull(request { runs.getStorage(saved.key) }).stores.isEmpty())
        assertTrue(request { runs.items(saved.key, 0, 20) }.isEmpty())
        assertEquals(ObjectBackupRunStatus.PENDING, saved.status)
    }

    @Test
    fun `fresh request selects a distinct restore without dispatching`(): Unit = runBlocking {
        retain()
        val first = data(submit()).getValue("prepareRestoreConfigured").jsonObject
        val second = data(submit(mapOf("requestKey" to JsonPrimitive("another")))).getValue("prepareRestoreConfigured").jsonObject
        assertNotEquals(first.string("key"), second.string("key"))
        assertEquals(2, history().size)
    }

    @Test
    fun `duplicate logical mappings are rejected before retained storage is read`(): Unit = runBlocking {
        assertError(submit(mapOf("stores" to JsonArray(listOf(mapping("content", "target"), mapping("content", "target"))))), "Duplicate")
        assertTrue(history().isEmpty())
    }

    @Test
    fun `invalid paths missing mappings and unknown storage fail without preparing a run`(): Unit = runBlocking {
        retain()
        for (path in listOf("", "/absolute.json", "manifests/../recovery.json", "manifests//recovery.json")) {
            assertError(submit(mapOf("path" to JsonPrimitive(path))), "manifest path")
        }
        assertError(submit(mapOf("stores" to JsonArray(emptyList()))), "Storage mapping")
        assertError(submit(mapOf("backupKey" to JsonPrimitive("unknown"))), "unknown")
        assertTrue(history().isEmpty())
    }

    @Test
    fun `missing or malformed manifest fails without saving restore intent`(): Unit = runBlocking {
        assertTrue("errors" in submit())
        write(destination, path, "not json")
        assertTrue("errors" in submit())
        assertTrue(history().isEmpty())
        assertFalse(directory.resolve("target").toFile().exists())
    }

    @Test
    fun `anonymous ordinary and restricted credentials cannot read a retained manifest`(): Unit = runBlocking {
        retain()
        val ordinary = ImpersonatedAuthenticationContext(Principal(), emptyList())
        for (authentication in listOf(anonymous, ordinary, scoped(listOf("content:read")), scoped(listOf("security:manage"), emptySet()))) {
            val response = submit(authentication = authentication)
            assertTrue("errors" in response, response.toString())
        }
        assertTrue(history().isEmpty())
    }

    @Test
    fun `super administrators and management scoped credentials may prepare from retained storage`(): Unit = runBlocking {
        retain()
        val sa = ImpersonatedAuthenticationContext(Principal(), listOf(Group(name = "sa", description = "", type = GroupType.SYSTEM)))
        for ((index, authentication) in listOf(sa, scoped(listOf("security:manage"))).withIndex()) {
            data(submit(mapOf("requestKey" to JsonPrimitive("authorized-$index")), authentication))
        }
        assertEquals(2, history().size)
    }

    private suspend fun retain() = write(destination, path, json.encodeToString(ObjectBackupManifest.serializer(), inventory))

    private suspend fun history() = request { runs.list(ObjectBackupOperation.RESTORE, inventory.sourceId, "recovery", inventory.scope, 0, 20) }

    private fun mapping(store: String, storageKey: String) = buildJsonObject { put("store", store); put("storageKey", storageKey) }

    private suspend fun submit(
        changes: Map<String, JsonElement> = emptyMap(),
        authentication: AuthenticationContext = admin,
    ): JsonObject = execute("""
        mutation PrepareRestore(
            ${'$'}requestKey: String!, ${'$'}destinationId: String!, ${'$'}backupKey: String!,
            ${'$'}path: String!, ${'$'}stores: [ObjectBackupStoreMappingInput!]!
        ) {
            backups { objectRuns { prepareRestoreConfigured(
                requestKey: ${'$'}requestKey, destinationId: ${'$'}destinationId, backupKey: ${'$'}backupKey,
                path: ${'$'}path, stores: ${'$'}stores
            ) { key status version path operation inventory { id } storage { backupKey stores { store storageKey } } } } }
        }
    """, authentication, JsonObject(buildJsonObject {
        put("requestKey", "restore-request"); put("destinationId", "recovery"); put("backupKey", "retained"); put("path", path)
        put("stores", JsonArray(listOf(mapping("content", "target"))))
    } + changes))

    private fun assertError(response: JsonObject, detail: String) {
        val errors = assertNotNull(response["errors"], response.toString()).jsonArray
        assertTrue(errors.any { detail in it.jsonObject.string("message") }, response.toString())
    }
}
