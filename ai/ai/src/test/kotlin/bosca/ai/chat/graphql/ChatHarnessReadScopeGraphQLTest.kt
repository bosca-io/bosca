@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ai.chat.graphql

import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatSession
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.harness.KitHarnessService
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.server.*
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID
import io.mockk.*
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class ChatHarnessReadScopeGraphQLTest {
    @Test
    fun `runtime token cannot list or read its principals other conversations`() = runTest {
        ProviderRegistry.clear()
        val principal = Principal(id = UUID.random(), anonymous = false)
        val session = ChatSession(UUID.random(), principal.id, agentKey = "kit", title = "Private parent conversation")
        val secret = "private parent context outside the delegated brief"
        val history = mockk<ChatHistoryService> {
            coEvery { getSessions(principal.id) } returns listOf(session)
            coEvery { getSession(session.id) } returns session
            coEvery { getMessages(session.id) } returns listOf(ChatHistoryMessage(sessionId = session.id,
                author = "user", event = JsonPrimitive(secret)))
        }
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        provides<Json> { Json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("ChatHarnessReadScopeGraphQLTest") }
        try {
            val sdl = listOf("chat.graphqls", "kit_context.graphqls").joinToString("\n") {
                requireNotNull(javaClass.getResourceAsStream("/graphql/$it")).bufferedReader().use { reader -> reader.readText() }
            }
            val graphql = GraphQL(ExecutableSchema.fromSdl("""
                scalar UUID
                scalar DateTime
                scalar JSON
                type Query { ok: Boolean }
                type Mutation { ok: Boolean }
                type Subscription { ok: Boolean }
            """.trimIndent() + sdl, runtimeWiring {
                scalar("UUID", ExtendedScalars.Uuid)
                scalar("DateTime", ExtendedScalars.DateTime)
                scalar("JSON", ExtendedScalars.Json)
                type("Query") { field("chatSessions") { ChatSessions } }
                type(ChatSessionsControllerDispatcher(ChatSessionsController(history, GroupEvaluator(mockk<SecurityService>()))).type)
                type(ChatSessionControllerDispatcher(ChatSessionController(history, mockk<KitHarnessService>(relaxed = true))).type)
                type(ChatHistoryMessageControllerDispatcher(ChatHistoryMessageController()).type)
            }))
            suspend fun execute(query: String, identity: AuthenticatedPrincipal) = graphql.execute(GraphQLRequest(query,
                context = GraphQLContext(mapOf("authenticationContext" to object : AuthenticationContext(null, null) {
                    override fun principal() = identity
                }))))
            val queries = listOf("{ chatSessions { all { title messages { event } } } }",
                "{ chatSessions { session(id: \"${session.id}\") { title messages { event } } } }")
            val allowedCallers = listOf(AuthenticatedPrincipal(principal, emptyList()),
                ScopedAuthenticatedPrincipal(principal, emptyList(), listOf("ai:execute"), emptySet(), 43),
                ScopedAuthenticatedPrincipal(principal, emptyList(), null, emptySet(), 44))
            val runtime = ScopedAuthenticatedPrincipal(principal, emptyList(), listOf("content:view", "ai:delegate"), emptySet(), 42)
            for (query in queries) {
                for (caller in allowedCallers) {
                    val allowed = execute(query, caller)
                    assertTrue(allowed.errors.isEmpty(), allowed.errors.toString())
                    assertContains(allowed.data.toString(), secret)
                }
                val denied = execute(query, runtime)
                assertTrue(denied.errors.isNotEmpty(), "Runtime credentials could read another conversation: ${denied.data}")
                assertFalse(denied.data.toString().contains(secret))
            }
            val foreign = ScopedAuthenticatedPrincipal(principal.copy(id = UUID.random()), emptyList(),
                listOf("ai:execute"), emptySet(), 45)
            val foreignRead = execute(queries.last(), foreign)
            assertTrue(foreignRead.errors.isEmpty(), foreignRead.errors.toString())
            assertFalse(foreignRead.data.toString().contains(secret))
        } finally { ProviderRegistry.clear() }
    }
}
