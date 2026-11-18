package bosca.system.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class InfrastructureActionsTest {
    private fun context(handler: suspend (String, JsonObject) -> JsonElement) = RenderContext(gql = object : GraphQLClient {
        override suspend fun execute(query: String, variables: JsonObject?, operationName: String?, token: String?): JsonElement = handler(requireNotNull(operationName), variables ?: JsonObject(emptyMap()))
    })
    @Test fun `unconfirmed infrastructure mutations never reach the API`() = runTest {
        val ctx = context { _, _ -> error("Must not execute") }
        val pg = PostgresConsoleModel(true)
        pg.terminateBackend("1", false, ctx)
        assertTrue(pg.feedback.failed)
        val nats = NatsConsoleModel(true, operationKind = "purgeStream", operationResource = "stream")
        nats.confirmOperation(false, ctx)
        assertTrue(nats.feedback.failed)
        val search = MeilisearchConsoleModel(true)
        search.prepareOperation("deleteIndex", "index", "")
        search.confirmOperation(false, ctx)
        assertTrue(search.feedback.failed)
    }
    @Test fun `unavailable monitoring remains visible and cancellation propagates`() = runTest {
        val model = NatsConsoleModel(true)
        model.refresh(context { _, _ -> error("Monitoring endpoint unavailable") })
        assertTrue(model.feedback.failed)
        assertEquals("Monitoring endpoint unavailable", model.feedback.message)
        assertFailsWith<CancellationException> { model.refresh(context { _, _ -> throw CancellationException() }) }
    }
}
