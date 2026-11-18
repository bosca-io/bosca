package bosca.ai.kit.harness.runtime

import bosca.ai.chat.graphql.*
import bosca.ai.graphql.*
import bosca.graphql.server.*
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real schema and generated dispatchers call the same transactional service used by the Runner. */
class KitDelegationGraphQLIntegrationTest {
    @Test fun `narrow delegation scope wires typed input output defaults and runtime credential binding`(): Unit = runBlocking {
        KitDelegationFixture().use { f ->
            f.initialize()
            val schema = listOf("delegations.graphqls", "kit_context.graphqls").joinToString("\n") { resource ->
                requireNotNull(javaClass.getResourceAsStream("/graphql/$resource")).bufferedReader().use { it.readText() }
            }
            val groups = GroupEvaluator(f.security)
            val engine = GraphQL(ExecutableSchema.fromSdl("""
                scalar UUID
                scalar DateTime
                scalar Long
                type Query { ai: Ai! }
                type Mutation { ai: AiMutation! }
                type Ai { delegations: KitDelegations! }
                type AiMutation { delegations: KitDelegationsMutation! }
            """.trimIndent() + schema, runtimeWiring {
                scalar("UUID", ExtendedScalars.Uuid); scalar("DateTime", bosca.graphql.scalars.DateTime.Type); scalar("Long", ExtendedScalars.Long)
                type("Query") { field("ai") { Ai } }; type("Mutation") { field("ai") { AiMutation } }
                type("Ai") { field("delegations", AiControllerDispatcher(AiController()).type.fieldResolvers.getValue("delegations")) }
                type("AiMutation") { field("delegations", AiMutationControllerDispatcher(AiMutationController()).type.fieldResolvers.getValue("delegations")) }
                type(KitDelegationsControllerDispatcher(KitDelegationsController(f.service, groups)).type)
                type(KitDelegationsMutationControllerDispatcher(KitDelegationsMutationController(f.service, groups)).type)
                type(KitDelegationControllerDispatcher(KitDelegationController()).type)
                type(KitDelegationPolicyControllerDispatcher(KitDelegationPolicyController()).type)
                type(KitDelegationResultControllerDispatcher(KitDelegationResultController()).type)
                type(KitDelegationArtifactControllerDispatcher(KitDelegationArtifactController()).type)
            }))
            suspend fun execute(query: String, authentication: AuthenticationContext = f.auth()) = engine.execute(GraphQLRequest(query,
                context = GraphQLContext(mapOf("authenticationContext" to authentication))))
            val id = UUID.random()
            val start = """mutation { ai { delegations { start(sessionId: "${f.sessionId}", input: {requestId: "$id", brief: "Inspect", expectedResult: "Answer", profile: "claude", runtimeSeconds: 30, artifacts: ["${f.source.id}"], context: []}) { id childSessionId status partial deadline } } } }"""
            val admitted = execute(start)
            assertTrue(admitted.errors.isEmpty(), admitted.errors.toString())
            assertContains(admitted.data.toString(), "QUEUED")
            assertContains(admitted.data.toString(), id.toString())
            val settings = execute("""{ ai { delegations { settings(sessionId: "${f.sessionId}") { profiles maxDepth maxRuntimeSeconds totalRuntimeSeconds } list(sessionId: "${f.sessionId}") { id } } } }""")
            assertTrue(settings.errors.isEmpty(), settings.errors.toString()); assertContains(settings.data.toString(), "claude")
            val denied = execute(start, f.auth(tokenScopes = listOf("content:view")))
            assertTrue(denied.errors.any { it.message == "Unauthorized access" }, denied.errors.toString())
            assertTrue(execute(start, f.auth(909)).errors.isNotEmpty())
            f.request {
                val child = assertNotNull(f.repository.get(id))
                f.sent(child.childSessionId, 202)
                val submit = execute("""mutation { ai { delegations { submit(sessionId: "${child.childSessionId}", output: {summary: "Answer", artifacts: [{metadataId: "${f.source.id}"}]}) { status partial result { summary artifacts { metadataId label } } } } } }""", f.auth(202))
                assertTrue(submit.errors.isEmpty(), submit.errors.toString())
                assertContains(submit.data.toString(), "\"partial\":true")
                assertContains(submit.data.toString(), "Answer")
                f.service.finish(assertNotNull(f.runtimes.get(child.childSessionId)), assertNotNull(f.runtimes.turn(child.childTurnId)), "COMPLETED", "")
            }
            val read = execute("""{ ai { delegations { get(sessionId: "${f.sessionId}", id: "$id") { status partial result { summary } } } } }""")
            assertTrue(read.errors.isEmpty(), read.errors.toString()); assertContains(read.data.toString(), "COMPLETED")
            f.request { assertEquals(1, f.repository.tree(f.turn.id).size) }
        }
    }
}
