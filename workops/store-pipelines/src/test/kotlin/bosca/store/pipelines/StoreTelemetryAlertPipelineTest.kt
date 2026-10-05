@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.store.pipelines

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.builtin.ConditionNode
import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.SendSlackNode
import bosca.pipelines.builtin.SendWebhookNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.requireCompleted
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Proves the documented low-rating alert is composition, not another bespoke engine surface. */
class StoreTelemetryAlertPipelineTest {
    private val server = MockWebServer().apply { start() }
    private val secrets = mockk<PipelineSecretService>()
    private val play = mockk<PlayPublisher>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<PipelineSecretService> { secrets }
        provides<PlayPublisher> { play }
        coEvery { secrets.resolveForExecution("play", false) } returns "play-credential"
        coEvery { secrets.resolveForExecution("slack", false) } returns server.url("/alert").toString()
        coEvery { secrets.resolveForExecution("analytics", false) } returns server.url("/analytics").toString()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        server.close()
    }

    @Test
    fun `scheduled telemetry pipeline ingests reviews and sends low rating alert through existing nodes`() = runTest {
        coEvery { play.reviews("play-credential", "io.example.app", 25) } returns listOf(
            PlayReviewResult("review-1", 2, "Login is broken", "Pat", "en-US", 123),
        )
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        server.enqueue(MockResponse.Builder().code(202).body("accepted").build())
        val pipeline = Pipeline(
            id = Uuid.random(),
            name = "Low store rating alert",
            acceptedInputType = "JSON",
            schedule = "0 */15 * * * ?",
            nodes = listOf(
                InputNode(id = "input", acceptedType = "JSON"),
                PlayReviewsNode(
                    id = "reviews",
                    applicationId = "io.example.app",
                    appVersion = "1.2.3",
                    maxResults = 25,
                    credentialSecretName = "play",
                ),
                ConditionNode(id = "low-rating", expression = "\$min(reviews.rating) <= 2"),
                SendSlackNode(id = "notify", webhookSecret = "slack"),
                JsonataNode(
                    id = "analytics-event",
                    expression = """{"events":[{"created":${'$'}millis(),"type":"interaction","element":{"type":"store.reviews","extras":${'$'}}}],"sent":${'$'}millis(),"sent_micros":0}""",
                ),
                SendWebhookNode(id = "ingest", urlSecret = "analytics"),
            ),
            edges = listOf(
                PipelineEdge("reviews-to-condition", "reviews", "low-rating"),
                PipelineEdge("condition-to-slack", "low-rating", "notify", sourcePort = "true"),
                PipelineEdge("reviews-to-event", "reviews", "analytics-event"),
                PipelineEdge("event-to-ingest", "analytics-event", "ingest"),
            ),
        )

        PipelineExecutorImpl().execute(
            pipeline,
            PipelineValue.ofJson(JsonPrimitive("scheduled")),
            PipelineContext(AuthenticationContext(null, null), Json),
        ).requireCompleted()

        assertEquals("0 */15 * * * ?", pipeline.schedule)
        val requests = List(2) { server.takeRequest() }.associateBy { it.target }
        assertTrue("Login is broken" in requests.getValue("/alert").body?.utf8().orEmpty())
        val analyticsBody = requests.getValue("/analytics").body?.utf8().orEmpty()
        assertTrue("store.reviews" in analyticsBody)
        assertTrue("io.example.app" in analyticsBody)
    }
}
