package bosca.backup.graphql

import bosca.backup.BackupJobQueueNames
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupOperation
import bosca.backup.ObjectBackupRun
import bosca.backup.ObjectBackupRunStatus
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.sharedqueue.jobs.JobQueue
import bosca.storage.service.ObjectStorageService
import io.mockk.confirmVerified
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Exercises typed inventory preparation through the real GraphQL and persistence boundaries. */
@OptIn(InternalDI::class)
class ObjectBackupRunPreparationGraphQLIntegrationTest : ObjectBackupGraphQLTest() {
    @Test
    fun `typed variables prepare backup intent with all manifest fields and ordered checkpoints`(): Unit = runBlocking {
        val declared = inventory.copy(id = "prepared-backup")
        val value = prepare(declared)
        val saved = assertNotNull(request { runs.get(value.string("key")) })

        assertEquals(declared, saved.inventory)
        assertEquals(ObjectBackupOperation.BACKUP, saved.operation)
        assertEquals(ObjectBackupRunStatus.PENDING, saved.status)
        assertEquals("destination-1", saved.destinationId)
        assertEquals("manifests/${saved.key}.json", value.string("path"))
        assertEquals("PENDING", value.string("status"))
        assertEquals("BACKUP", value.string("operation"))
        assertEquals(0, value.getValue("attempts").jsonPrimitive.int)
        assertEquals(0L, value.getValue("version").jsonPrimitive.long)
        assertEquals(JsonNull, value.getValue("error"))
        assertEquals(JsonNull, value.getValue("result"))
        assertEquals(
            json.encodeToJsonElement(ObjectBackupManifest.serializer(), declared),
            value.getValue("inventory"),
        )
        val items = value.getValue("items").jsonArray
        assertEquals(listOf(first, second), request { runs.items(saved.key, 0, 100) }.map { it.payload })
        assertEquals(listOf(first.key, second.key), items.map { it.jsonObject.getValue("payload").jsonObject.string("key") })
        assertTrue(items.all { it.jsonObject.string("status") == "PENDING" })
        assertEquals(2L, value.getValue("summary").jsonObject.getValue("pending").jsonPrimitive.long)
        assertEquals(0L, value.getValue("summary").jsonObject.getValue("verifiedBytes").jsonPrimitive.long)
    }

    @Test
    fun `inline manifest inputs preserve quoted and unicode source keys`(): Unit = runBlocking {
        val declared = inventory.copy(
            id = "inline", objects = listOf(first.copy(key = "images/\"café\".png")),
        )
        val response = execute("""
            mutation {
                backups { objectRuns { prepare(destinationId: "destination-1", inventory: {
                    id: "inline", sourceId: "source-1", scope: "content", created: "2026-10-16T00:00:00Z",
                    format: "bosca-object-backup", version: 1,
                    objects: [{ store: "content", key: "images/\"café\".png", revision: "revision-1",
                        length: 4, sha256: "${first.sha256}", payloadPath: "${first.payloadPath}" }]
                }) { key inventory { objects { key } } } } }
            }
        """)
        val value = data(response).getValue("prepare").jsonObject

        assertEquals(declared.objects.single().key, value.getValue("inventory").jsonObject
            .getValue("objects").jsonArray.single().jsonObject.string("key"))
        assertEquals(declared, request { runs.get(value.string("key")) }?.inventory)
    }

    @Test
    fun `restore preparation fixes input path and original-key checkpoints separately from backup`(): Unit = runBlocking {
        val value = prepare(inventory, restorePath = "recovery/inventory.json")
        val saved = assertNotNull(request { runs.get(value.string("key")) })

        assertNotEquals(run.key, saved.key)
        assertEquals(ObjectBackupOperation.RESTORE, saved.operation)
        assertEquals("RESTORE", value.string("operation"))
        assertEquals("recovery/inventory.json", value.string("path"))
        assertEquals(inventory, saved.inventory)
        val restored = request { runs.items(saved.key, 0, 100) }
        val backedUp = request { runs.items(run.key, 0, 100) }
        assertEquals(inventory.objects.map { it.key }, restored.map { it.payload.payloadPath })
        assertTrue(restored.all { it.operation == ObjectBackupOperation.RESTORE })
        assertTrue(restored.none { item -> backedUp.any { it.key == item.key } })
        assertEquals(run, request { runs.get(run.key) })
    }

    @Test
    fun `empty inventory remains pending with zero totals until execution`(): Unit = runBlocking {
        for (path in listOf(null, "recovery/empty.json")) {
            val value = prepare(inventory.copy(id = "empty", objects = emptyList()), restorePath = path)

            assertEquals("PENDING", value.string("status"))
            assertEquals(JsonNull, value.getValue("result"))
            assertTrue(value.getValue("items").jsonArray.isEmpty())
            assertTrue(value.getValue("summary").jsonObject.values.all { it.jsonPrimitive.long == 0L })
        }
    }

    @Test
    fun `shared payload references retain each logical object and its checkpoint`(): Unit = runBlocking {
        val shared = first.copy(key = "items/shared")
        val value = prepare(inventory.copy(id = "shared", objects = listOf(first, shared)))
        val items = request { runs.items(value.string("key"), 0, 100) }

        assertEquals(listOf(first, shared), items.map { it.payload })
        assertEquals(2, items.map { it.key }.toSet().size)
        assertEquals(8L, value.getValue("summary").jsonObject.getValue("totalBytes").jsonPrimitive.long)
    }

    @Test
    fun `Long input lengths are preserved beyond Int range`(): Unit = runBlocking {
        val entry = first.copy(key = "items/large", length = 5_000_000_000L, payloadPath = "objects/large")
        val declared = inventory.copy(id = "large", objects = listOf(entry))
        val value = prepare(declared)

        assertEquals(declared, request { runs.get(value.string("key")) }?.inventory)
        assertEquals(entry.length, value.getValue("summary").jsonObject.getValue("totalBytes").jsonPrimitive.long)
        assertEquals(entry.length, value.getValue("inventory").jsonObject.getValue("objects").jsonArray
            .single().jsonObject.getValue("length").jsonPrimitive.long)
    }

    @Test
    fun `repeated backup and restore preparation preserves pending intent and timestamps`(): Unit = runBlocking {
        for (path in listOf(null, "recovery/repeat.json")) {
            val declared = inventory.copy(id = "repeat")
            val firstResponse = prepare(declared, restorePath = path)
            val before = assertNotNull(request { runs.get(firstResponse.string("key")) })
            val items = request { runs.items(before.key, 0, 100) }
            val repeated = prepare(declared, restorePath = path)

            assertEquals(firstResponse, repeated)
            assertEquals(before, request { runs.get(before.key) })
            assertEquals(items, request { runs.items(before.key, 0, 100) })
        }
    }

    @Test
    fun `preparing a completed run again preserves the recorded outcome`(): Unit = runBlocking {
        val completed = complete(run)
        val items = request { runs.items(run.key, 0, 100) }
        val value = prepare(inventory)

        assertEquals("COMPLETED", value.string("status"))
        assertEquals(completed.attempts, value.getValue("attempts").jsonPrimitive.int)
        assertEquals(completed.version, value.getValue("version").jsonPrimitive.long)
        assertEquals(2L, value.getValue("result").jsonObject.getValue("completed").jsonPrimitive.long)
        assertEquals(completed, request { runs.get(run.key) })
        assertEquals(items, request { runs.items(run.key, 0, 100) })
    }

    @Test
    fun `preparing an active or cancelled run again does not reset or restart it`(): Unit = runBlocking {
        val active = request { runs.start(run.key, run.version) }
        val checkpoint = request { runs.items(run.key, 0, 1) }.single()
        request { progress.start(checkpoint.key, checkpoint.version) }
        val activeItems = request { runs.items(run.key, 0, 100) }

        val current = prepare(inventory)

        assertEquals("RUNNING", current.string("status"))
        assertEquals(active, request { runs.get(run.key) })
        assertEquals(activeItems, request { runs.items(run.key, 0, 100) })

        val declared = inventory.copy(id = "cancelled")
        val selected = prepare(declared, restorePath = "recovery/cancelled.json")
        val queued = assertNotNull(request { runs.get(selected.string("key")) })
        val cancelled = request { runs.cancel(queued.key, queued.version, "operator cancelled") }
        val cancelledItems = request { runs.items(cancelled.key, 0, 100) }

        val repeated = prepare(declared, restorePath = cancelled.path)

        assertEquals("CANCELLED", repeated.string("status"))
        assertEquals("operator cancelled", repeated.string("error"))
        assertEquals(cancelled, request { runs.get(cancelled.key) })
        assertEquals(cancelledItems, request { runs.items(cancelled.key, 0, 100) })
    }

    @Test
    fun `changed immutable intent and restore paths fail without modifying the existing run`(): Unit = runBlocking {
        val original = prepare(inventory, restorePath = "recovery/original.json")
        val before = assertNotNull(request { runs.get(original.string("key")) })
        val items = request { runs.items(before.key, 0, 100) }

        assertError(submit(inventory.copy(created = "2026-10-17T00:00:00Z")), "Conflicting object backup run intent")
        assertError(submit(inventory, restorePath = "recovery/changed.json"), "Conflicting object backup run intent")

        assertEquals(run, request { runs.get(run.key) })
        assertEquals(before, request { runs.get(before.key) })
        assertEquals(items, request { runs.items(before.key, 0, 100) })
    }

    @Test
    fun `a conflicting existing checkpoint rolls back the entire preparation`(): Unit = runBlocking {
        val newItem = first.copy(key = "items/new", payloadPath = "objects/new")
        val conflicting = second.copy(length = second.length + 1)
        val declared = inventory.copy(id = "rollback", objects = listOf(newItem, conflicting))
        val before = request { runs.items(run.key, 0, 100) }

        assertError(submit(declared), "Conflicting object backup revision intent")

        assertEquals(listOf(run), history())
        assertNull(request { progress.get(ObjectBackupOperation.BACKUP, inventory.sourceId, "destination-1", inventory.scope, newItem) })
        assertEquals(before, request { runs.items(run.key, 0, 100) })
    }

    @Test
    fun `invalid manifest and object claims fail before creating any run or checkpoints`(): Unit = runBlocking {
        val invalid = listOf(
            inventory.copy(id = "bad-format", format = "other") to "Unsupported object backup format",
            inventory.copy(id = "bad-version", version = 2) to "Unsupported object backup version",
            inventory.copy(id = "bad-time", created = "yesterday") to "Invalid object backup creation time",
            inventory.copy(id = "") to "Object backup identity is required",
            inventory.copy(id = "bad-source", sourceId = " ") to "Object backup source identity is required",
            inventory.copy(id = "bad-scope", scope = "") to "Object backup scope is required",
            inventory.copy(id = "bad-digest", objects = listOf(first.copy(sha256 = "etag-2"))) to "Invalid SHA-256 digest",
            inventory.copy(id = "bad-length", objects = listOf(first.copy(length = -1))) to "Invalid object length",
            inventory.copy(id = "bad-key", objects = listOf(first.copy(payloadPath = "objects/../payload"))) to "Invalid backup payload path",
            inventory.copy(id = "duplicate", objects = listOf(first, first)) to "Duplicate object key",
            inventory.copy(id = "duplicate-revision", objects = listOf(first, first.copy(revision = "revision-2"))) to "Duplicate object key",
            inventory.copy(id = "conflicting-payload", objects = listOf(first, second.copy(payloadPath = first.payloadPath))) to "Conflicting payload identity",
        )
        for ((declared, error) in invalid) assertError(submit(declared), error)
        assertError(submit(inventory.copy(id = "no-destination"), destinationId = " "), "destination identity")

        assertEquals(listOf(run), history())
        assertEquals(2, request { progress.list(ObjectBackupOperation.BACKUP, inventory.sourceId, "destination-1", inventory.scope, 0, 100) }.size)
    }

    @Test
    fun `restore preparation rejects noncanonical paths and ambiguous original object targets`(): Unit = runBlocking {
        for (path in listOf("", "/absolute.json", "recovery/../input.json", "recovery//input.json", first.payloadPath)) {
            assertError(submit(inventory, restorePath = path), if (path == first.payloadPath) "conflicts with a payload" else "manifest path")
        }
        val duplicateTarget = inventory.copy(id = "ambiguous", objects = listOf(first, first.copy(revision = "revision-2")))
        assertError(submit(duplicateTarget, restorePath = "recovery/ambiguous.json"), "Duplicate object key")
        val traversal = inventory.copy(id = "bad-original", objects = listOf(first.copy(key = "../outside")))
        assertError(submit(traversal, restorePath = "recovery/traversal.json"), "Invalid backup payload path")

        assertEquals(listOf(run), history())
        assertTrue(history(ObjectBackupOperation.RESTORE).isEmpty())
    }

    @Test
    fun `anonymous ordinary and restricted tokens cannot prepare either operation`(): Unit = runBlocking {
        val ordinary = ImpersonatedAuthenticationContext(Principal(), emptyList())
        for (authentication in listOf(anonymous, ordinary, scoped(listOf("content:read")), scoped(listOf("security:manage"), emptySet()))) {
            for (path in listOf(null, "recovery/denied.json")) {
                val response = submit(inventory.copy(id = "denied"), restorePath = path, authentication = authentication)
                assertRuntimeError(response)
            }
        }

        assertEquals(listOf(run), history())
        assertTrue(history(ObjectBackupOperation.RESTORE).isEmpty())
    }

    @Test
    fun `super administrators and management-scoped administrators can prepare either operation`(): Unit = runBlocking {
        val sa = ImpersonatedAuthenticationContext(Principal(), listOf(Group(name = "sa", description = "", type = GroupType.SYSTEM)))
        for ((index, authentication) in listOf(sa, scoped(listOf("security:manage"))).withIndex()) {
            for (path in listOf(null, "recovery/allowed-$index.json")) {
                val declared = inventory.copy(id = "allowed-$index")
                val value = prepare(declared, restorePath = path, authentication = authentication)

                assertEquals(declared, request { runs.get(value.string("key")) }?.inventory)
            }
        }
    }

    @Test
    fun `preparation does not open storage or enqueue a transfer`(): Unit = runBlocking {
        val storage = provide<ObjectStorageService>()
        val queue = provide<JobQueue>(name = BackupJobQueueNames.backupJobQueue)

        prepare(inventory.copy(id = "no-io"))
        prepare(inventory.copy(id = "no-io"), restorePath = "recovery/unread.json")

        confirmVerified(storage, queue)
    }

    @Test
    fun `typed input schema requires the full portable manifest and object claims`(): Unit = runBlocking {
        for ((name, fields) in listOf(
            "ObjectBackupManifestInput" to setOf("id", "sourceId", "scope", "created", "objects", "version", "format"),
            "BackupObjectInput" to setOf("store", "key", "revision", "length", "sha256", "payloadPath"),
        )) {
            val response = execute("{ __type(name: \"$name\") { kind inputFields { name type { kind } } } }")
            assertFalse("errors" in response, response.toString())
            val type = response.getValue("data").jsonObject.getValue("__type")
            assertTrue(type is JsonObject, response.toString())
            assertEquals("INPUT_OBJECT", type.string("kind"))
            val inputs = type.getValue("inputFields").jsonArray
            assertEquals(fields, inputs.map { it.jsonObject.string("name") }.toSet())
            assertTrue(inputs.all { it.jsonObject.getValue("type").jsonObject.string("kind") == "NON_NULL" })
        }
    }

    @Test
    fun `missing null and unknown input fields are rejected before preparation`(): Unit = runBlocking {
        val declared = inventory.copy(id = "invalid-input")
        val encoded = json.encodeToJsonElement(ObjectBackupManifest.serializer(), declared).jsonObject
        val objects = encoded.getValue("objects").jsonArray
        val variants = listOf(
            JsonObject(encoded - "format"),
            JsonObject(encoded + ("version" to JsonNull)),
            JsonObject(encoded + ("objects" to JsonArray(listOf(JsonNull)))),
            JsonObject(encoded + ("objects" to JsonArray(listOf(JsonObject(objects.first().jsonObject - "sha256"))))),
            JsonObject(encoded + ("unexpected" to JsonNull)),
        )
        for (input in variants) {
            val response = execute("""
                mutation Invalid(${'$'}inventory: ObjectBackupManifestInput!) {
                    backups { objectRuns { prepare(inventory: ${'$'}inventory, destinationId: "destination-1") { key } } }
                }
            """, variables = buildJsonObject { put("inventory", input) })

            val errors = assertNotNull(response["errors"], response.toString()).jsonArray
            assertTrue(errors.isNotEmpty(), response.toString())
            assertTrue(errors.all {
                (it.jsonObject["path"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content == "inventory"
            }, response.toString())
        }
        assertEquals(listOf(run), history())
    }

    private suspend fun prepare(
        declared: ObjectBackupManifest,
        destinationId: String = "destination-1",
        restorePath: String? = null,
        authentication: AuthenticationContext = admin,
    ): JsonObject = data(submit(declared, destinationId, restorePath, authentication))
        .getValue(if (restorePath == null) "prepare" else "prepareRestore").jsonObject

    private suspend fun submit(
        declared: ObjectBackupManifest,
        destinationId: String = "destination-1",
        restorePath: String? = null,
        authentication: AuthenticationContext = admin,
    ): JsonObject {
        val method = if (restorePath == null) "prepare" else "prepareRestore"
        val pathVariable = if (restorePath == null) "" else ", ${'$'}path: String!"
        val pathArgument = if (restorePath == null) "" else ", path: ${'$'}path"
        return execute("""
            mutation Prepare(${'$'}inventory: ObjectBackupManifestInput!, ${'$'}destinationId: String!$pathVariable) {
                backups { objectRuns { $method(inventory: ${'$'}inventory, destinationId: ${'$'}destinationId$pathArgument) {
                    key operation status attempts version path error result { completed }
                    inventory { id sourceId scope created version format objects { store key revision length sha256 payloadPath } }
                    items { key status operation payload { key payloadPath } }
                    summary { total pending running completed failed totalBytes verifiedBytes }
                } } }
            }
        """, authentication, buildJsonObject {
            put("inventory", json.encodeToJsonElement(ObjectBackupManifest.serializer(), declared))
            put("destinationId", destinationId)
            restorePath?.let { put("path", it) }
        })
    }

    private suspend fun history(operation: ObjectBackupOperation = ObjectBackupOperation.BACKUP): List<ObjectBackupRun> =
        request { runs.list(operation, inventory.sourceId, "destination-1", inventory.scope, 0, 100) }

    private fun assertRuntimeError(response: JsonObject) {
        val errors = assertNotNull(response["errors"], response.toString()).jsonArray
        assertTrue(errors.isNotEmpty(), response.toString())
        assertTrue(errors.all { it.jsonObject["path"] is JsonArray }, response.toString())
    }

    private fun assertError(response: JsonObject, expected: String) {
        assertRuntimeError(response)
        assertTrue(response.getValue("errors").jsonArray.any {
            it.jsonObject.string("message").contains(expected)
        }, response.toString())
    }
}
