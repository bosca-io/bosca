package bosca.backup.pipeline

import bosca.backup.PostgresBackupManifest
import bosca.backup.PostgresBackupService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.node.BackupPipelineNodeSerializersProvider
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Generated node contracts and boundary behavior around the existing configured restore service. */
@OptIn(InternalDI::class)
class PostgresRestoreNodeTest {
    private val service = mockk<PostgresBackupService>()
    private val group = Group(name = "administrators", description = "", type = GroupType.SYSTEM)
    private val admin = authentication(AuthenticatedPrincipal(Principal(), listOf(group)))
    private val manifest = PostgresBackupManifest(
        "snapshot", "old-instance", "source", "2026-10-19T00:00:00Z", "2026-10-19T00:00:01Z",
        "exported", "1:2:", "18", 180000, "UTF8", emptyMap(), 5, "a".repeat(64),
    )
    private val json = Json {
        serializersModule = SerializersModule { include(BackupPipelineNodeSerializersProvider().module) }
    }

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<GroupEvaluator> { GroupEvaluator(mockk()) }
        provides<PostgresBackupService> { service }
        coEvery { service.restoreConfigured("native/snapshot", "retained") } returns manifest
    }

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    @Test
    fun `emits the exact restored native manifest only after configured service success`(): Unit = runBlocking {
        assertEquals(manifest, perform().decode(PostgresBackupManifest.serializer(), json))
        coVerify(exactly = 1) { service.restoreConfigured("native/snapshot", "retained") }
    }

    @Test
    fun `anonymous ordinary and restricted tokens fail before restore service access`(): Unit = runBlocking {
        for (caller in listOf(
            AuthenticationContext(null, null), authentication(AuthenticatedPrincipal(Principal(), emptyList())),
            authentication(ScopedAuthenticatedPrincipal(Principal(), listOf(group), listOf("content:edit"), null, 1)),
            authentication(ScopedAuthenticatedPrincipal(Principal(), listOf(group), listOf("security:manage"), emptySet(), 1)),
        )) assertFailsWith<SecurityException> { perform(caller = caller) }
        coVerify(exactly = 0) { service.restoreConfigured(any(), any()) }
    }

    @Test
    fun `service account and management scoped callers use the same restore boundary`(): Unit = runBlocking {
        val sa = authentication(AuthenticatedPrincipal(Principal(), listOf(Group(name = "sa", description = "", type = GroupType.SYSTEM))))
        val management = authentication(ScopedAuthenticatedPrincipal(Principal(), listOf(group), listOf("security:manage"), null, 1))
        for (caller in listOf(sa, management)) {
            assertEquals(manifest, perform(caller = caller).decode(PostgresBackupManifest.serializer(), json))
        }
        coVerify(exactly = 2) { service.restoreConfigured("native/snapshot", "retained") }
    }

    @Test
    fun `missing blank and incompatible native paths fail before restore`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { perform(inputs = NodeInputs(emptyMap())) }
        assertFailsWith<IllegalArgumentException> { perform(inputs = input(" ")) }
        assertFailsWith<Exception> {
            perform(inputs = NodeInputs(mapOf("path" to PipelineValue.ofJson(JsonObject(emptyMap())))))
        }
        coVerify(exactly = 0) { service.restoreConfigured(any(), any()) }
    }

    @Test
    fun `native paths and catalog identifiers reach the service literally`(): Unit = runBlocking {
        val path = "native/\"σ\"/snapshot"
        coEvery { service.restoreConfigured(path, "backup=σ") } returns manifest
        perform(PostgresRestoreNode("restore", backupKey = "backup=σ"), inputs = input(path))
        coVerify(exactly = 1) { service.restoreConfigured(path, "backup=σ") }
    }

    @Test
    fun `cancellation native failures and post-commit cleanup failures propagate without output`(): Unit = runBlocking {
        for (failure in listOf(CancellationException("stopped"), IOException("native failed"), IOException("close failed after commit"))) {
            coEvery { service.restoreConfigured(any(), any()) } throws failure
            val observed = assertFailsWith<Exception> { perform() }
            assertTrue(observed === failure)
        }
    }

    @Test
    fun `dry run records declared selection without resolving authorization or restore services`(): Unit = runBlocking {
        ProviderRegistry.clear()
        val trace = DryRunTrace()
        val result = node().run(PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace), input())
        assertNull(assertIs<NodeResult.Output>(result).value)
        val action = trace.actions.getValue("restore").jsonObject
        assertEquals(JsonPrimitive("postgresBackup.restore"), action["action"])
        assertEquals(JsonPrimitive("native/snapshot"), action["path"])
        assertEquals(JsonPrimitive("retained"), action["backupKey"])
    }

    @Test
    fun `dry run tolerates an unconnected path without inventing a manifest`(): Unit = runBlocking {
        ProviderRegistry.clear()
        val trace = DryRunTrace()
        val result = node().run(PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace), NodeInputs(emptyMap()))
        assertNull(assertIs<NodeResult.Output>(result).value)
        assertTrue("path" !in trace.actions.getValue("restore").jsonObject)
    }

    @Test
    fun `generated catalog and graph codecs expose ordinary settings typed ports and a queued action`() {
        val entry = BackupPipelineNodeSerializersProvider().descriptors.single { it.key == "postgresBackup.restore" }
        assertEquals(listOf("backupKey"), entry.settings.map { it.name })
        assertTrue(entry.settings.all { it.required && it.control == SettingControl.TEXT })
        assertEquals("path", entry.inputs.single().name)
        assertEquals(PostgresBackupManifest.serializer().descriptor.serialName, entry.outputs.single().type)
        val restored = assertIs<PostgresRestoreNode>(json.decodeFromString(
            PipelineNode.serializer(), json.encodeToString(PipelineNode.serializer(), node()),
        ))
        assertEquals("retained", restored.backupKey)
        assertTrue(restored.willSuspend)
    }

    private fun node() = PostgresRestoreNode("restore", backupKey = "retained")

    private fun input(path: String = "native/snapshot") = NodeInputs(mapOf("path" to PipelineValue.ofJson(JsonPrimitive(path))))

    private suspend fun perform(
        selected: PostgresRestoreNode = node(),
        inputs: NodeInputs = input(),
        caller: AuthenticationContext = admin,
    ): PipelineValue = assertNotNull(assertIs<NodeResult.Output>(selected.run(PipelineContext(caller, json), inputs)).value)

    private fun authentication(principal: AuthenticatedPrincipal) = object : AuthenticationContext(null, null) {
        override fun principal() = principal
    }
}
