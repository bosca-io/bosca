@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.repository

import bosca.pipelines.service.PipelineRunNodeResult
import bosca.pipelines.service.PipelineRunResultStoreImpl
import bosca.pipelines.service.PipelineSecretServiceImpl
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import bosca.pipelines.testutil.inMemoryObjectStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Value-type and small-guard branch coverage for the pipeline persistence layer:
 *  - [PipelinePermission] — the generated `equals` arms (same-ref, wrong-type, and each field
 *    differing) plus the [PipelinePermission.entityId] alias getter.
 *  - [PipelineRecord] — the `@Serializable data class` synthetic default-args constructor, driven by
 *    constructing the record once per optional field with only THAT field omitted, so every default
 *    mask bit's "provided" arm is exercised (the existing all-defaults / all-provided constructions
 *    only flip the complementary arms).
 *  - [PipelineRunResultStoreImpl.get] — the `?.let { decode }` both arms (a present row decodes; a
 *    missing row short-circuits to null).
 *  - [PipelineSecretServiceImpl] — the encrypt/decrypt round-trip that initializes the lazy key from
 *    the `System.getenv(...) ?: DEV_DEFAULT_KEY` default (env-absent) arm.
 */
class RepositoryValueTypesTest {

    private val json = Json

    // === PipelinePermission: equals arms + entityId alias =========================================

    private val pid = UUID.parse("00000000-0000-0000-0000-0000000000a1")
    private val gid = UUID.parse("00000000-0000-0000-0000-0000000000b2")

    private fun permission(
        pipelineId: UUID = pid,
        groupId: UUID = gid,
        action: PermissionAction = PermissionAction.EXECUTE,
    ) = PipelinePermission(pipelineId = pipelineId, groupId = groupId, action = action)

    @Test
    fun `entityId aliases the pipeline id`() {
        assertEquals(pid, permission().entityId)
    }

    @Test
    fun `equals is true for the same reference`() {
        val p = permission()
        @Suppress("KotlinConstantConditions")
        assertEquals(true, p.equals(p))
    }

    @Test
    fun `equals is true for a distinct but field-identical permission`() {
        assertEquals(permission(), permission())
        // hashCode contract holds for equal values.
        assertEquals(permission().hashCode(), permission().hashCode())
    }

    @Test
    fun `equals is false against a different type and against null`() {
        val p = permission()
        assertNotEquals<Any?>(p, "not a permission")
        assertNotEquals<Any?>(p, null)
    }

    @Test
    fun `equals is false when the pipeline id differs`() {
        assertNotEquals(permission(), permission(pipelineId = UUID.random()))
    }

    @Test
    fun `equals is false when the group id differs`() {
        assertNotEquals(permission(), permission(groupId = UUID.random()))
    }

    @Test
    fun `equals is false when the action differs`() {
        assertNotEquals(permission(), permission(action = PermissionAction.VIEW))
    }

    // === PipelineRecord: per-field single-omission default-args constructions ======================
    //
    // Each construction supplies every optional EXCEPT one, so the omitted field takes its default
    // (the "use default" mask arm) while all the others take the "provided" mask arm — together
    // covering both arms of every optional's default mask bit.

    private val graph: JsonObject = buildJsonObject { put("nodes", "x") }

    /** All optional fields populated with non-default values (used as the per-omission baseline). */
    private fun fullArgs(): Map<String, Any?> = mapOf(
        "id" to UUID.random(),
        "description" to "desc",
        "triggered" to true,
        "key" to "k",
        "api" to true,
        "public" to true,
        "schedule" to "0 0 * * *",
        "maxConcurrentRuns" to 3,
        "maxRunsPerMinute" to 7,
        "version" to 9L,
        "gitRepositoryId" to UUID.random(),
        "gitPath" to "pipelines/x.yaml",
        "lastSyncError" to "sync boom",
        "deletedAt" to java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
    )

    @Test
    fun `record omitting only id keeps the rest provided`() {
        val r = PipelineRecord(
            name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertEquals(UUID.NIL, r.id)
        assertEquals("d", r.description)
    }

    @Test
    fun `record omitting only description keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertEquals("", r.description)
        assertEquals(true, r.triggered)
    }

    @Test
    fun `record omitting only triggered keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertEquals(false, r.triggered)
        assertEquals("k", r.key)
    }

    @Test
    fun `record omitting only key keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertEquals("", r.key)
        assertEquals(true, r.api)
    }

    @Test
    fun `record omitting only api keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertEquals(false, r.api)
        assertEquals(true, r.public)
    }

    @Test
    fun `record omitting only public keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertEquals(false, r.public)
        assertEquals("c", r.schedule)
    }

    @Test
    fun `record omitting only schedule keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertNull(r.schedule)
        assertEquals(1, r.maxConcurrentRuns)
    }

    @Test
    fun `record omitting only maxConcurrentRuns keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertNull(r.maxConcurrentRuns)
        assertEquals(2, r.maxRunsPerMinute)
    }

    @Test
    fun `record omitting only maxRunsPerMinute keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertNull(r.maxRunsPerMinute)
        assertEquals(4L, r.version)
    }

    @Test
    fun `record omitting only version keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertEquals(0L, r.version)
        assertEquals("g", r.gitPath)
    }

    @Test
    fun `record omitting only gitRepositoryId keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitPath = "g", lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertNull(r.gitRepositoryId)
        assertEquals("g", r.gitPath)
    }

    @Test
    fun `record omitting only gitPath keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), lastSyncError = "e",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertNull(r.gitPath)
        assertEquals("e", r.lastSyncError)
    }

    @Test
    fun `record omitting only lastSyncError keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g",
            deletedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        )
        assertNull(r.lastSyncError)
        assertNotNull(r.deletedAt)
    }

    @Test
    fun `record omitting only deletedAt keeps the rest provided`() {
        val r = PipelineRecord(
            id = UUID.random(), name = "P", acceptedInputType = "JSON", graph = graph,
            description = "d", triggered = true, key = "k", api = true, public = true,
            schedule = "c", maxConcurrentRuns = 1, maxRunsPerMinute = 2, version = 4L,
            gitRepositoryId = UUID.random(), gitPath = "g", lastSyncError = "e",
        )
        assertNull(r.deletedAt)
        assertEquals("e", r.lastSyncError)
    }

    private fun assertNotNull(value: Any?) = assertNotEquals<Any?>(null, value)

    // === PipelineRunResultStoreImpl: object-storage round-trip ====================

    @Test
    fun `result store round-trips the value and its routing port`() = runTest {
        val store = PipelineRunResultStoreImpl(inMemoryObjectStorage(), json)
        val runId = UUID.random()
        store.put(runId, "n1", JsonPrimitive("done"), "alreadyExists")

        val result = store.get(runId, "n1")
        assertEquals(JsonPrimitive("done"), result?.value)
        assertEquals("alreadyExists", result?.port)
    }

    @Test
    fun `result store get surfaces a genuinely absent object as an error`() = runTest {
        // The run always stages before it can resume, so an absent staged object is a real error, not
        // a silent "no output" — the read surfaces it rather than masking it as null.
        val store = PipelineRunResultStoreImpl(inMemoryObjectStorage(), json)
        assertFailsWith<java.io.FileNotFoundException> { store.get(UUID.random(), "missing") }
    }

    @Test
    fun `result store remove discards the staged object`() = runTest {
        val store = PipelineRunResultStoreImpl(inMemoryObjectStorage(), json)
        val runId = UUID.random()
        store.put(runId, "n1", JsonPrimitive("v"), null)
        store.remove(runId, "n1")
        assertFailsWith<java.io.FileNotFoundException> { store.get(runId, "n1") }
    }

    // === PipelineSecretServiceImpl: lazy key init via the env-absent default arm ===================

    @Test
    fun `secret service encrypts and decrypts using the dev-default key when the env var is absent`() = runTest {
        // PIPELINE_SECRET_KEY is not set in the test JVM, so the lazy key falls back to DEV_DEFAULT_KEY
        // (the `System.getenv(...) ?: DEV_DEFAULT_KEY` elvis default arm). Round-trip proves the key
        // initialized and AES/GCM works.
        val repo = mockk<PipelineSecretRepository>()
        val captured = slot<bosca.pipelines.model.PipelineSecret>()
        coEvery { repo.upsert(capture(captured)) } answers { captured.captured }
        val service = PipelineSecretServiceImpl(repo)

        val stored = service.setSecret("api-key", "round-trip-me")
        assertNotEquals("round-trip-me", stored.encryptedValue)

        coEvery { repo.findByName("api-key") } returns captured.captured
        assertEquals("round-trip-me", service.resolve("api-key"))
    }
}
