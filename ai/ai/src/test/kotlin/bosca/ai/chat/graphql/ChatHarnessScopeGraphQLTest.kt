@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatDispatcher
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.harness.*
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.server.*
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID
import io.mockk.*
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Exercises the real schema, generated dispatcher and authorization boundary. */
class ChatHarnessScopeGraphQLTest {
    @Test fun `runtime token cannot recursively create send activate or control Kit through GraphQL`() = runTest {
        ProviderRegistry.clear()
        val principal = Principal(id = UUID.random(), anonymous = false)
        val session = ChatSession(UUID.random(), principal.id, agentKey = "kit")
        val history = mockk<ChatHistoryService> { coEvery { getSession(session.id) } returns session }
        val harness = mockk<KitHarnessService>(relaxed = true)
        val chat = mockk<ChatDispatcher>(relaxed = true)
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        provides<Json> { Json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("ChatHarnessScopeGraphQLTest") }
        try {
            val dispatcher = ChatSessionsMutationControllerDispatcher(ChatSessionsMutationController(history, harness, chat))
            val sdl = listOf("chat.graphqls", "kit_context.graphqls").joinToString("\n") { resource ->
                requireNotNull(javaClass.getResourceAsStream("/graphql/$resource")).bufferedReader().use { it.readText() }
            }
            val graphql = GraphQL(ExecutableSchema.fromSdl("""
                scalar UUID
                scalar DateTime
                scalar JSON
                type Query { ok: Boolean, controls: KitHarnessSettings }
                type Mutation { ok: Boolean }
                type Subscription { ok: Boolean }
            """.trimIndent() + sdl, runtimeWiring {
                scalar("UUID", ExtendedScalars.Uuid)
                scalar("DateTime", ExtendedScalars.DateTime)
                scalar("JSON", ExtendedScalars.Json)
                type("Mutation") { field("chatSessions") { ChatSessionsMutation } }
                type(dispatcher.type)
                type(KitHarnessSettingsControllerDispatcher(KitHarnessSettingsController()).type)
                type(KitModelOptionControllerDispatcher(KitModelOptionController()).type)
                type("Query") { field("controls") { KitHarnessSettings("policy", "kit", "SYSTEM", true, listOf("kit"),
                    capabilityVersion = "caps", model = "small", reasoning = "medium", models = listOf(KitModelOption("small", "Small")),
                    reasoningOptions = listOf(KitModelOption("medium", "Medium"))) } }
            }))
            suspend fun execute(operation: String, identity: AuthenticatedPrincipal) = graphql.execute(GraphQLRequest(
                "mutation { chatSessions { $operation } }", context = GraphQLContext(mapOf("authenticationContext" to
                    object : AuthenticationContext(null, null) { override fun principal() = identity }))))
            val restricted = ScopedAuthenticatedPrincipal(principal, emptyList(), listOf("content:view", "content:edit"), emptySet(), 42)
            for (operation in listOf("create(session: {agentKey: \"kit\"}) { id }", "activateHarness(sessionId: \"${session.id}\")",
                "archiveHarness(sessionId: \"${session.id}\")", "cancel(sessionId: \"${session.id}\")",
                "addHarnessSource(sessionId: \"${session.id}\", source: {kind: SPEC, id: \"${UUID.random()}\"})",
                "removeHarnessSource(sessionId: \"${session.id}\", source: {kind: SPEC, id: \"${UUID.random()}\"})",
                "clearHarnessSources(sessionId: \"${session.id}\")",
                "send(sessionId: \"${session.id}\", message: {role: \"user\", parts: [{type: \"text\", text: \"recurse\"}]})")) {
                val result = execute(operation, restricted)
                assertTrue(result.errors.any { it.message.contains("ai:execute") }, result.errors.toString())
            }
            coVerify(exactly = 0) { history.createSession(any(), any()) }
            coVerify(exactly = 0) { harness.activate(any(), any()) }
            coVerify(exactly = 0) { chat.dispatch(any(), any(), any()) }
            coVerify(exactly = 0) { harness.selectContext(any(), any(), any()) }
            coVerify(exactly = 0) { harness.clearContext(any()) }
            val output = graphql.execute(GraphQLRequest("{ controls { capabilityVersion model reasoning modelSource reasoningSource models { value name } reasoningOptions { value name } } }",
                context = GraphQLContext(mapOf("authenticationContext" to object : AuthenticationContext(null, null) {
                    override fun principal() = AuthenticatedPrincipal(principal, emptyList())
                }))))
            assertTrue(output.errors.isEmpty(), output.errors.toString())
            assertContains(output.data.toString(), "Small")
            assertContains(output.data.toString(), "Medium")
            val allowed = ScopedAuthenticatedPrincipal(principal, emptyList(), listOf("ai:execute"), emptySet(), 43)
            assertTrue(execute("activateHarness(sessionId: \"${session.id}\")", allowed).errors.isEmpty())
            assertTrue(execute("archiveHarness(sessionId: \"${session.id}\")", AuthenticatedPrincipal(principal, emptyList())).errors.isEmpty())
            val selection = "setHarnessSettings(sessionId: \"${session.id}\", choice: {profile: \"kit\", policyVersion: \"policy\", model: \"small\", reasoning: \"medium\", capabilityVersion: \"caps\"})"
            assertTrue(execute(selection, restricted).errors.any { it.message.contains("ai:execute") })
            assertTrue(execute(selection, allowed).errors.isEmpty())
            val expected = KitHarnessChoice("kit", "policy", model = "small", reasoning = "medium", capabilityVersion = "caps")
            coVerify { harness.verifySettings(any(), session.id, expected); harness.setSettings(session.id, expected) }
            coEvery { harness.verifySettings(any(), session.id, expected) } throws IllegalArgumentException("Harness capabilities changed; reload settings")
            assertEquals("Internal server error", execute(selection, allowed).errors.single().message)
            coVerify(exactly = 1) { harness.setSettings(session.id, expected) }
            val foreign = ScopedAuthenticatedPrincipal(principal.copy(id = UUID.random()), emptyList(), listOf("ai:execute"), emptySet(), 44)
            assertTrue(execute("activateHarness(sessionId: \"${session.id}\")", foreign).errors.any { it.message.contains("Access denied") })
            val source = KitContextSelection(KitContextSourceKind.GIT_FILE, UUID.random(), "main", "docs/plan.md")
            val add = "addHarnessSource(sessionId: \"${session.id}\", source: {kind: GIT_FILE, id: \"${source.id}\", ref: \"main\", path: \"docs/plan.md\"})"
            assertTrue(execute(add, allowed).errors.isEmpty())
            coVerify { harness.selectContext(session.id, source, false) }
            assertTrue(execute(add, foreign).errors.any { it.message.contains("Access denied") })
            coVerify(exactly = 1) { harness.selectContext(any(), any(), any()) }
            assertTrue(execute("clearHarnessSources(sessionId: \"${session.id}\")", allowed).errors.isEmpty())
            coVerify { harness.clearContext(session.id) }
        } finally { ProviderRegistry.clear() }
    }
}
