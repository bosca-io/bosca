package bosca.ai.kit.agents

import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import bosca.ai.kit.agents.chat.ChatResponse
import bosca.ai.kit.agents.description.DescriptionResponse
import bosca.ai.kit.agents.readingtime.ReadingTimeResponse
import bosca.ai.kit.agents.routing.RouteResponse
import bosca.ai.kit.agents.analytics.AnalyticsResponse
import bosca.ai.kit.agents.topics.TopicMatch
import bosca.ai.kit.agents.topics.TopicsResponse
import bosca.ai.kit.agents.writer.WriterDecision
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.KitToolContext
import bosca.ai.kit.tools.sql.ExecuteQueryTool
import bosca.ai.kit.tools.sql.SqlQuery
import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.Text as BibleText
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart
import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.toJson
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.documents.Content
import bosca.graphql.GraphQLService
import bosca.graphql.schema.GraphQLSchema
import bosca.documents.HtmlNode
import bosca.documents.NodeConverter
import bosca.security.service.AuthenticationContext
import bosca.ai.kit.agents.graphql.GraphQLSummary
import bosca.ai.kit.agents.image.ImageResponse
import bosca.ai.kit.agents.image.ImageServices
import bosca.ai.kit.agents.image.KitImageClient
import bosca.ai.kit.agents.pipeline.PipelineResponse
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.agents.script.ScriptResponse
import bosca.ai.kit.agents.script.ScriptServices
import bosca.ai.kit.agents.session.InMemoryKitSessionService
import bosca.ai.kit.tools.graphql.GraphQLQueryTool
import bosca.ai.kit.tools.image.GenerateImageTool
import bosca.ai.kit.tools.pipeline.ListPipelinesTool
import bosca.ai.kit.tools.script.ListScriptsTool
import bosca.scripting.model.Script
import bosca.scripting.service.ScriptService
import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * End-to-end proof of Kit's two acceptance flows through the **GOAP planner**, with only the
 * LLM's decisions mocked. The planner re-plans from real state after every action, so the
 * branch (`WRITE` vs `QUERY`) is decided at run time by the `route` action's structured output —
 * exactly the behaviour these tests pin down:
 *
 * 1. *Biblical article* — `route → fetch_scripture → write_document → save_document`, ending
 *    with a real `bosca/v-document` [Metadata] persisted via [MetadataService.add].
 * 2. *Total users* — `route → answer_query`, ending with a SQL result the planner reports.
 */
class KitFlowTest {

    private val exactScriptureText = "Stored exact verse nineteen."

    private fun installedTestBible() = Bible(
        metadataId = Uuid.parse("650e8400-e29b-41d4-a716-446655440000"),
        version = 1,
        systemId = "verified-test-bible",
        variant = "default",
        defaultVariant = true,
        name = "Verified Test Bible",
        nameLocal = "",
        description = "",
        abbreviation = "VTB",
        abbreviationLocal = "",
        styles = buildJsonObject { },
    )

    private fun installedTestChapter() = BibleChapter(
        metadataId = Uuid.parse("650e8400-e29b-41d4-a716-446655440000"),
        version = 1,
        variant = "default",
        bookUsfm = "JAS",
        usfm = "JAS.1",
        components = ComponentContainer(
            ContainerType.PARAGRAPH,
            listOf(
                VerseStart(Reference("JAS.1.19")),
                BibleText(exactScriptureText, null),
                VerseEnd(),
            ),
            null,
        ).toJson(),
        sort = 1,
    )

    private val model = LLModel(OpenAILLMProvider, "mock", listOf(LLMCapability.Tools, LLMCapability.Completion))
    private val models = KitModels(model)

    // Stand-in for the platform Json (lenient parsing of LLM output); the real one is DI-provided.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // Kit's own Json (the platform Json + Koog's reflective polymorphic types), as DI builds it.
    private val kitJson = KitJson(koogJson(json))
    // The script sub-agent's services; relaxed since these flows don't route to SCRIPT.
    private val scriptServices = mockk<ScriptServices>(relaxed = true)
    // The image sub-agent's services; relaxed since these flows don't route to IMAGE.
    private val imageServices = mockk<ImageServices>(relaxed = true)
    // The GraphQL sub-agent's service; relaxed since these flows don't route to GRAPHQL.
    private val graphQLService = mockk<GraphQLService>(relaxed = true)
    private val auth = mockk<AuthenticationContext>(relaxed = true)

    @Test
    fun `WRITE flow plans route-fetch-write-save and persists a bosca v-document`() = runTest {
        val bibleService = mockk<BibleService>()
        val metadataService = mockk<MetadataService>()
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        // Scripture source: one bible, one resolved reference, one chapter the writer quotes.
        val bible = mockk<Bible>(relaxed = true)
        val reference = mockk<Reference>(relaxed = true)
        val chapter = mockk<BibleChapter>(relaxed = true)
        every { chapter.usfm } returns "JHN.3"
        coEvery { bibleService.getBibles() } returns listOf(bible)
        coEvery { bibleService.getReferences(any(), any()) } returns listOf(reference)
        coEvery { bibleService.getChapter(any<Bible>(), any<Reference>()) } returns chapter

        // The saved metadata the platform returns; capture what Kit asked it to persist.
        val created = mockk<Metadata>(relaxed = true)
        val savedId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        coEvery { created.id } returns savedId
        coEvery { created.version } returns 1
        coEvery { created.contentType } returns "bosca/v-document"
        val captured = slot<MetadataInput>()
        coEvery { metadataService.add(null, null, capture(captured)) } returns created

        // Mock ONLY the LLM's decisions: the intent (structured), the writer's needs-Scripture check
        // (structured), and the article HTML body (PLAIN text — never JSON, so a long document cannot be
        // truncated mid-string and lost to a parse error).
        val routingJson = Json.encodeToString(
            RouteResponse(route = KitRoute.WRITE, title = "On God's Love", references = listOf("John 3:16")),
        )
        val articleHtml = "<h1>On God's Love</h1>" +
            "<p>Scripture reveals the depth of God's love.</p>" +
            "<blockquote><p>For God so loved the world (JHN.3.16)</p></blockquote>"
        // Route already supplied the reference, so fetch_scripture runs first and the writer has its
        // Scripture: it decides it needs nothing more, then writes the HTML body as a plain-text turn.
        val writerDecisionJson = Json.encodeToString(WriterDecision(needsScripture = false))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMAnswer(writerDecisionJson) onRequestContains "Writing task"
            mockLLMAnswer(articleHtml) onRequestContains "Now write the complete document"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) {
            kit.run(kitRequest("Please write a biblically grounded article about God's love, quoting John 3:16."))
        }

        // The planner reached the goal by saving — and the response is a Document (not text),
        // identifying the new metadata. Kit's output shape fits the request.
        val document = assertIs<KitResponse.Document>(response, "WRITE should return a Document response, got: $response")
        assertEquals(savedId, document.metadataId)

        // The document Kit persisted is a real bosca/v-document built from the writer's HTML.
        val input = captured.captured
        assertEquals("bosca/v-document", input.contentType)
        assertEquals("On God's Love", input.name)
        assertEquals("On God's Love", input.document?.title)
        assertNotNull(input.document?.content, "the tiptap Content object must be attached")
        // Prove the writer SUB-AGENT returned a structured document, not text: its HTML must have
        // become a non-empty tiptap tree (the <h1>/<p>/<blockquote> nodes), built inside the agent.
        val rootChildren = input.document?.content?.document?.content
        assertTrue(rootChildren?.isNotEmpty() == true, "writer must return a non-empty structured Content tree, got: $rootChildren")
    }

    @Test
    fun `QUERY flow routes to the SQL sub-agent which runs execute_query and reports the result`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>()
        coEvery { sqlQuery.executeQuery(any(), any()) } returns listOf(mapOf("total" to 42L))

        // Only the tool's NAME + arg serialization are used here (to script the LLM's tool call);
        // the sub-agent builds and runs its OWN real ExecuteQueryTool against the sqlQuery above.
        val executeQuery = ExecuteQueryTool(mockk(relaxed = true))

        val routingJson = Json.encodeToString(
            RouteResponse(route = KitRoute.QUERY, title = "", references = emptyList()),
        )
        // The SQL sub-agent's STRUCTURED final answer — data + visualization, not a sentence. Its
        // `query` field is deliberately a PARAPHRASE of what actually ran: the model's recollection
        // must NOT win over the recorded execution when Kit sources the answer.
        val sqlResponseJson = Json.encodeToString(
            AnalyticsResponse(
                summary = "There are 42 total users in the database.",
                query = "select COUNT(*) from users",
                columns = listOf("total"),
                rows = listOf(listOf("42")),
                visualization = "NUMBER",
                savedQueryId = "query-id",
                savedQueryKey = "total-users",
                annotations = listOf(
                    bosca.ai.kit.agents.analytics.InvestigationAnnotation(
                        sequence = 1,
                        sql = "select count(*) as total from users",
                        purpose = "Count current users.",
                        conclusion = "The count is 42.",
                    ),
                ),
            ),
        )
        val executor = getMockExecutor {
            // Planner's route action decides QUERY.
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            // Inside the SQL sub-agent (structuredOutputWithToolsStrategy): first turn calls execute_query…
            mockLLMToolCall(executeQuery, ExecuteQueryTool.Input("SELECT count(*) AS total FROM users")) onRequestContains "Answer this analytics question"
            // …then, from the tool result (which carries rowCount), it emits the structured SqlResponse.
            mockLLMAnswer(sqlResponseJson) onRequestContains "rowCount"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("How many total users are in the database?")) }

        // The sub-agent returned STRUCTURED analytics (summary + data + visualization), not text.
        val analytics = assertIs<KitResponse.Analytics>(response, "QUERY should return an Analytics response, got: $response")
        assertTrue(analytics.summary.contains("42"), "summary should state the figure: ${analytics.summary}")
        assertEquals(listOf("total"), analytics.columns)
        assertEquals(listOf(listOf("42")), analytics.rows)
        // Prove the sub-agent really ran its own ExecuteQueryTool against the warehouse with the SQL
        // the LLM chose (the structured rows above came from a mocked answer; this pins the real call).
        coVerify(exactly = 1) { sqlQuery.executeQuery("SELECT count(*) AS total FROM users", any()) }
        // The answer's source is the EXECUTED statement (recorded by the tool), not the paraphrased
        // `query` the model's structured answer claimed — the user sees exactly what ran.
        assertEquals("SELECT count(*) AS total FROM users", analytics.query)
        assertEquals("query-id", analytics.savedQueryId)
        assertEquals("total-users", analytics.savedQueryKey)
        assertEquals(1, analytics.investigation.size)
        assertEquals("Count current users.", analytics.investigation.single().purpose)
        assertEquals("The count is 42.", analytics.investigation.single().conclusion)
    }

    @Test
    fun `writer asks for Scripture, fetch runs, and the writer is re-invoked with the chapters`() = runTest {
        val bibleService = mockk<BibleService>()
        val metadataService = mockk<MetadataService>()
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        val bible = mockk<Bible>(relaxed = true)
        val reference = mockk<Reference>(relaxed = true)
        val chapter = mockk<BibleChapter>(relaxed = true)
        every { chapter.usfm } returns "JHN.3"
        coEvery { bibleService.getBibles() } returns listOf(bible)
        coEvery { bibleService.getReferences(any(), any()) } returns listOf(reference)
        coEvery { bibleService.getChapter(any<Bible>(), any<Reference>()) } returns chapter

        val created = mockk<Metadata>(relaxed = true)
        val savedId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        coEvery { created.id } returns savedId
        coEvery { created.version } returns 1
        coEvery { created.contentType } returns "bosca/v-document"
        coEvery { metadataService.add(null, null, any()) } returns created

        // Route does NOT supply any references, so write_document runs first with no Scripture.
        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.WRITE, title = "On God's Love", references = emptyList()))
        // First writer decision (no Scripture in the prompt): it asks for the reference it needs.
        val needsScriptureJson = Json.encodeToString(WriterDecision(needsScripture = true, references = listOf("John 3:16")))
        // Second writer decision (Scripture now present — the prompt contains the chapter usfm "JHN"): it has
        // everything it needs, so the HTML body then arrives as a separate plain-text turn.
        val hasScriptureJson = Json.encodeToString(WriterDecision(needsScripture = false))
        val articleHtml = "<h1>On God's Love</h1><blockquote><p>For God so loved the world (JHN.3.16)</p></blockquote>"

        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMAnswer(needsScriptureJson) onCondition { it.contains("Writing task") && !it.contains("JHN") }
            mockLLMAnswer(hasScriptureJson) onCondition { it.contains("Writing task") && it.contains("JHN") }
            mockLLMAnswer(articleHtml) onRequestContains "Now write the complete document"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) {
            kit.run(kitRequest("Write a short devotional about God's love."))
        }

        // Kit still finished by saving a document…
        val document = assertIs<KitResponse.Document>(response, "WRITE should return a Document response, got: $response")
        assertEquals(savedId, document.metadataId)
        // …and it got there via the round-trip: the reference the WRITER asked for drove fetch_scripture.
        coVerify(exactly = 1) { bibleService.getReferences(any(), "John 3:16") }
    }

    @Test
    fun `an ambiguous request is routed to CLARIFY and Kit asks the user a question`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        // The router is not confident, so rather than assume it asks a clarifying question.
        val question = "Did you want me to write something, or are you asking about your content?"
        val routingJson = Json.encodeToString(
            RouteResponse(route = KitRoute.CLARIFY, question = question, rationale = "The request is ambiguous."),
        )
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("do the thing")) }

        // Kit returns a Question (not an answer) carrying exactly the router's clarifying question.
        val asked = assertIs<KitResponse.Question>(response, "an ambiguous request should yield a Question, got: $response")
        assertEquals(question, asked.text)
    }

    @Test
    fun `CHAT flow routes to the chat sub-agent and returns its reply`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.CHAT, rationale = "A conversational question."))
        val reply = "I help you create biblically-grounded content and explore your library."
        val chatJson = Json.encodeToString(ChatResponse(text = reply))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            // The ChatAgent's structured turn (no tool calls) — keyed off its system prompt, which is
            // unique to the chat sub-agent (the render no longer carries a "respond to" marker).
            mockLLMAnswer(chatJson) onRequestContains "Reply to the user"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("what can you do?")) }

        // route → chat (the ChatAgent sub-agent) → Kit reports its reply as Text.
        val text = assertIs<KitResponse.Text>(response, "CHAT should return a Text reply, got: $response")
        assertEquals(reply, text.text)
    }

    @Test
    fun `SCRIPTURE flow returns exact installed text with translation attribution`() = runTest {
        val bibleService = mockk<BibleService>()
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)
        val bible = installedTestBible()
        val reference = Reference("JAS.1.19")
        coEvery { bibleService.getBibles() } returns listOf(bible)
        coEvery { bibleService.getReferences(bible, "James 1:19") } returns listOf(reference)
        coEvery { bibleService.getChapter(bible, reference) } returns installedTestChapter()
        coEvery { bibleService.getHumanLong(bible, reference) } returns "James 1:19"

        val routingJson = Json.encodeToString(
            RouteResponse(
                route = KitRoute.SCRIPTURE,
                references = listOf("James 1:19"),
                translation = "VTB",
                rationale = "The user requested Bible text.",
            ),
        )
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) {
            kit.run(kitRequest("I'm feeling angry. What does James 1:19 say in the VTB?"))
        }

        val text = assertIs<KitResponse.Text>(response)
        assertEquals(
            "Here are passages from Verified Test Bible (VTB):\n\n" +
                "**James 1:19**\n" +
                "[19] $exactScriptureText\n\n" +
                "Source: Verified Test Bible (VTB), retrieved from Bosca's installed Bible content.",
            text.text,
        )
        coVerify(exactly = 1) { bibleService.getChapter(bible, reference) }
    }

    @Test
    fun `chat cannot answer Scripture from memory and hands exact retrieval to SCRIPTURE`() = runTest {
        val bibleService = mockk<BibleService>()
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)
        val bible = installedTestBible()
        val reference = Reference("JAS.1.19")
        coEvery { bibleService.getBibles() } returns listOf(bible)
        coEvery { bibleService.getReferences(bible, "James 1:19") } returns listOf(reference)
        coEvery { bibleService.getChapter(bible, reference) } returns installedTestChapter()
        coEvery { bibleService.getHumanLong(bible, reference) } returns "James 1:19"

        val routingJson = Json.encodeToString(
            RouteResponse(route = KitRoute.CHAT, rationale = "The router mistakenly treated it as conversation."),
        )
        val rememberedParaphrase = "Everyone should listen before getting mad."
        val chatJson = Json.encodeToString(
            ChatResponse(
                text = rememberedParaphrase,
                handOffTo = KitRoute.SCRIPTURE,
                references = listOf("James 1:19"),
                translation = "VTB",
            ),
        )
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMAnswer(chatJson) onRequestContains "Reply to the user"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) {
            kit.run(kitRequest("I'm feeling angry, what Bible verses do you have?"))
        }

        val text = assertIs<KitResponse.Text>(response)
        assertTrue(text.text.contains(exactScriptureText))
        assertTrue(!text.text.contains(rememberedParaphrase))
        assertTrue(text.text.contains("Source: Verified Test Bible (VTB)"))
        coVerify(exactly = 1) { bibleService.getChapter(bible, reference) }
    }

    @Test
    fun `chat hands a data question off to the analytics agent instead of answering it`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        // The router mis-reads it as conversational; the chat agent recognizes a data question and punts.
        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.CHAT, rationale = "Seems conversational."))
        val chatJson = Json.encodeToString(ChatResponse(text = "Let me pull those numbers.", handOffTo = KitRoute.QUERY))
        val sqlJson = Json.encodeToString(
            AnalyticsResponse(summary = "There are 42 users.", query = "select count(*) from users", columns = listOf("total"), rows = listOf(listOf("42")), visualization = "number"),
        )
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMAnswer(chatJson) onRequestContains "Reply to the user"
            mockLLMAnswer(sqlJson) onRequestContains "Answer this analytics question"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("how many users?")) }

        // route→CHAT, but chat hands off to QUERY → the planner re-routes, the analytics agent runs,
        // and Kit answers with Analytics rather than a chat reply.
        val analytics = assertIs<KitResponse.Analytics>(response, "a chat hand-off to QUERY should end in Analytics, got: $response")
        assertEquals("There are 42 users.", analytics.summary)
    }

    @Test
    fun `DESCRIBE flow summarizes the document in context into a Description`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        // A document is in context; Kit reads its text (provided inline) and summarizes it.
        val metadata = mockk<Metadata>(relaxed = true)
        coEvery { metadata.id } returns Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        coEvery { metadata.version } returns 1
        val content = Content(NodeConverter(bibleService).convertDocument(HtmlNode(html = "<p>Grace and truth came through Jesus Christ.</p>")))
        val document = DocumentInput(title = "Grace", content = content)

        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.DESCRIBE, rationale = "wants a description"))
        val descJson = Json.encodeToString(DescriptionResponse(description = "A short reflection on grace and truth in Christ."))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMAnswer(descJson) onRequestContains "Text:"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val request = kitRequest("describe this document").copy(metadata = metadata, document = document)
        val response = withContext(KitToolContext(auth)) { kit.run(request) }

        val description = assertIs<KitResponse.Description>(response, "DESCRIBE should return a Description, got: $response")
        assertEquals("A short reflection on grace and truth in Christ.", description.description)
    }

    @Test
    fun `TOPICS flow matches the document against the catalog's topics and returns them`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)
        val collectionService = mockk<CollectionService>(relaxed = true)

        val metadata = mockk<Metadata>(relaxed = true)
        coEvery { metadata.id } returns Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        coEvery { metadata.version } returns 1
        val content = Content(NodeConverter(bibleService).convertDocument(HtmlNode(html = "<p>Grace and truth came through Jesus Christ.</p>")))
        val document = DocumentInput(title = "Grace", content = content)

        // One candidate topic the catalog offers (collections tagged type = "Topic"); the agent matches it.
        val topicId = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val topic = mockk<Collection>(relaxed = true)
        coEvery { topic.id } returns topicId
        coEvery { topic.name } returns "Grace"
        coEvery { collectionService.find(any()) } returns listOf(topic)

        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.TOPICS, rationale = "wants topics"))
        val topicsJson = Json.encodeToString(TopicsResponse(topics = listOf(TopicMatch(id = topicId.toString(), name = "Grace"))))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMAnswer(topicsJson) onRequestContains "Available Topics"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), collectionService, sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val request = kitRequest("what topics fit this?").copy(metadata = metadata, document = document)
        val response = withContext(KitToolContext(auth)) { kit.run(request) }

        val topics = assertIs<KitResponse.Topics>(response, "TOPICS should return Topics, got: $response")
        // The match is resolved back to the real topic — id from the catalog, the catalog's own name.
        assertEquals(listOf(topicId), topics.topics.map { it.id })
        assertEquals("Grace", topics.topics.single().name)
        coVerify { collectionService.find(any()) }
    }

    @Test
    fun `READING_TIME flow estimates a reading time for the document in context`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        val metadata = mockk<Metadata>(relaxed = true)
        coEvery { metadata.id } returns Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        coEvery { metadata.version } returns 1
        val content = Content(NodeConverter(bibleService).convertDocument(HtmlNode(html = "<p>Grace and truth came through Jesus Christ.</p>")))
        val document = DocumentInput(title = "Grace", content = content)

        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.READING_TIME, rationale = "wants reading time"))
        val readingJson = Json.encodeToString(ReadingTimeResponse(totalWordCount = 7, readingTimeInMinutes = 1))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMAnswer(readingJson) onRequestContains "Text:"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val request = kitRequest("how long to read this?").copy(metadata = metadata, document = document)
        val response = withContext(KitToolContext(auth)) { kit.run(request) }

        val reading = assertIs<KitResponse.ReadingTime>(response, "READING_TIME should return ReadingTime, got: $response")
        assertEquals(7, reading.totalWordCount)
        assertEquals(1, reading.readingTimeInMinutes)
    }

    @Test
    fun `SCRIPT flow routes to the script sub-agent which runs a tool and reports the result`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        // The script service the real ListScriptsTool will query inside the sub-agent's tool loop.
        val scriptService = mockk<ScriptService>()
        coEvery { scriptService.getAll() } returns listOf(Script(key = "hello", name = "Hello", source = "main {}"))
        val scripts = ScriptServices(scriptService, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

        // Only the tool's NAME + arg serialization are used here to script the LLM's tool call; the
        // sub-agent builds and runs its OWN real ListScriptsTool against the scriptService above.
        val listScripts = ListScriptsTool(mockk(relaxed = true))

        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.SCRIPT, rationale = "manage scripts"))
        val scriptJson = Json.encodeToString(ScriptResponse(message = "There is 1 script: hello."))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            // Inside the script sub-agent: first turn calls list_scripts…
            mockLLMToolCall(listScripts, ListScriptsTool.Input("")) onRequestContains "Carry out this scripting request"
            // …then, from the tool result (which names the "hello" script), it emits the structured summary.
            mockLLMAnswer(scriptJson) onRequestContains "hello"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scripts, imageServices, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("list my scripts")) }

        // route → SCRIPT → the script sub-agent runs its tool loop and Kit reports the structured summary as Text.
        val text = assertIs<KitResponse.Text>(response, "SCRIPT should return a Text summary, got: $response")
        assertEquals("There is 1 script: hello.", text.text)
        // Prove the sub-agent really ran its own ListScriptsTool against the script service.
        coVerify(exactly = 1) { scriptService.getAll() }
    }

    @Test
    fun `PIPELINE flow routes to the pipeline specialist which discovers existing automation`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)
        val pipelineService = mockk<PipelineService>()
        coEvery { pipelineService.getAll() } returns listOf(
            Pipeline(
                id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
                name = "Welcome automation",
                acceptedInputType = "JSON",
                key = "welcome",
            ),
        )
        val pipelines = PipelineServices(pipelineService, graphQLService, mockk(relaxed = true), json)
        val listPipelines = ListPipelinesTool(pipelines)
        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.PIPELINE, rationale = "manage automation"))
        val responseJson = Json.encodeToString(
            PipelineResponse(
                message = "The existing pipeline is Welcome automation.",
                pipelineId = "550e8400-e29b-41d4-a716-446655440000",
                editorPath = "/pipelines/550e8400-e29b-41d4-a716-446655440000",
            ),
        )
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMToolCall(listPipelines, ListPipelinesTool.Input()) onRequestContains "Carry out this pipeline request"
            mockLLMAnswer(responseJson) onRequestContains "Welcome automation"
        }

        val kit = KitAgent(
            bibleService,
            metadataService,
            mockk<DocumentService>(relaxed = true),
            mockk<CollectionService>(relaxed = true),
            sqlQuery,
            scriptServices,
            imageServices,
            graphQLService,
            executor,
            models,
            kitJson,
            InMemoryKitSessionService(),
            mockk(relaxed = true),
            pipelineServices = pipelines,
        ).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("show me the welcome automation")) }

        val text = assertIs<KitResponse.Text>(response)
        assertTrue(text.text.contains("Welcome automation"))
        assertTrue(text.text.contains("/pipelines/550e8400-e29b-41d4-a716-446655440000"))
        coVerify(exactly = 1) { pipelineService.getAll() }
    }

    @Test
    fun `IMAGE flow routes to the image sub-agent which runs a tool and reports the result`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        // An UNCONFIGURED image client (no account) → the real generate_image tool degrades gracefully
        // instead of calling Gemini, which is exactly the path we can assert without network/credentials.
        val images = ImageServices(
            imageClient = KitImageClient(account = null, model = "test-model"),
            metadataService = metadataService,
            objectStorageService = mockk(relaxed = true),
            groupEvaluator = mockk(relaxed = true),
            collectionService = mockk(relaxed = true),
            documentService = mockk(relaxed = true),
            dataService = mockk(relaxed = true),
            documentTemplateService = mockk(relaxed = true),
            dataTemplateService = mockk(relaxed = true),
            collectionTemplateService = mockk(relaxed = true),
            metadataPermissionEvaluator = mockk(relaxed = true),
            collectionPermissionEvaluator = mockk(relaxed = true),
            imageService = mockk(relaxed = true),
        )

        // Only the tool's NAME + arg serialization are used here to script the LLM's tool call.
        val generateImage = GenerateImageTool(KitImageClient(null, "test-model"), metadataService, mockk(relaxed = true), mockk(relaxed = true))

        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.IMAGE, rationale = "wants an image"))
        val imageJson = Json.encodeToString(ImageResponse(message = "I couldn't generate the image: it isn't configured."))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            // Inside the image sub-agent: first turn calls generate_image…
            mockLLMToolCall(generateImage, GenerateImageTool.Input("a watercolor of a lighthouse")) onRequestContains "Carry out this image request"
            // …the real tool reports it isn't configured; from that result the agent emits its summary.
            mockLLMAnswer(imageJson) onRequestContains "not configured"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, images, graphQLService, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("make me a picture of a lighthouse")) }

        // route → IMAGE → the image sub-agent runs its tool loop and Kit reports the structured summary as Text.
        val text = assertIs<KitResponse.Text>(response, "IMAGE should return a Text summary, got: $response")
        assertEquals("I couldn't generate the image: it isn't configured.", text.text)
    }

    @Test
    fun `GRAPHQL flow runs graphql_query, answers from the result, and carries the exact data`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        // The platform GraphQL service the real graphql_query tool runs as the user. Its EXACT result is
        // what the model answers from AND what the agent carries through as `data`.
        val serverResult = buildJsonObject {
            put("data", buildJsonObject { put("collections", buildJsonObject { put("id", JsonPrimitive("collection-42")) }) })
        }
        val graphql = mockk<GraphQLService>()
        coEvery { graphql.execute(any(), any()) } returns serverResult
        // The agent resolves the returned type's SDL from the live schema (authoritative, not a model echo).
        val schema = mockk<GraphQLSchema>()
        every { schema.type(any()) } returns null
        coEvery { graphql.getSchema() } returns schema

        // Only the tool's NAME + arg serialization are used to script the LLM's tool call; the sub-agent
        // builds and runs its OWN real GraphQLQueryTool against the graphQLService above.
        val queryTool = GraphQLQueryTool(mockk(relaxed = true), json)

        // The model runs the query, then — having SEEN the result — answers + names the type. It never
        // supplies the data itself; the agent lifts that verbatim from the tool result.
        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.GRAPHQL, rationale = "manage content"))
        val summaryJson = Json.encodeToString(GraphQLSummary(message = "You have 1 collection (collection-42).", type = "[Collection]"))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            // First the sub-agent runs the query…
            mockLLMToolCall(queryTool, GraphQLQueryTool.Input(query = "query { collections { id } }")) onRequestContains "Handle this request against the Bosca GraphQL API"
            // …then, from the tool result (which carries the data), it answers + names the type.
            mockLLMAnswer(summaryJson) onRequestContains "collection-42"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphql, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("list all of my collections")) }

        // route → GRAPHQL → Kit returns a RICH GraphQL result: the answer, the EXACT data, and what it is.
        val gql = assertIs<KitResponse.GraphQL>(response, "GRAPHQL should return a GraphQL response, got: $response")
        assertEquals("You have 1 collection (collection-42).", gql.message)
        assertEquals("[Collection]", gql.type)
        // The data is the EXACT server response — lifted verbatim from the graphql_query tool result, not
        // re-typed by the model (the model's summary never contained it).
        assertEquals(serverResult, gql.data)
        // Prove the sub-agent really executed GraphQL as the user via the platform service.
        coVerify(exactly = 1) { graphql.execute(any(), any()) }
    }

    @Test
    fun `chat hands a what-exists platform lookup off to GRAPHQL instead of inventing an answer`() = runTest {
        val bibleService = mockk<BibleService>(relaxed = true)
        val metadataService = mockk<MetadataService>(relaxed = true)
        val sqlQuery = mockk<SqlQuery>(relaxed = true)

        // "What content templates are available?" — the router mis-reads it as conversational, but templates
        // are real platform data, so chat must NOT fabricate a list: it hands off to GRAPHQL to look them up.
        val serverResult = buildJsonObject {
            put("data", buildJsonObject { put("documentTemplates", buildJsonObject { put("name", JsonPrimitive("Devotional")) }) })
        }
        val graphql = mockk<GraphQLService>()
        coEvery { graphql.execute(any(), any()) } returns serverResult
        val schema = mockk<GraphQLSchema>()
        every { schema.type(any()) } returns null
        coEvery { graphql.getSchema() } returns schema

        val queryTool = GraphQLQueryTool(mockk(relaxed = true), json)

        val routingJson = Json.encodeToString(RouteResponse(route = KitRoute.CHAT, rationale = "Sounds like a capability question."))
        val chatJson = Json.encodeToString(ChatResponse(text = "Let me look those up.", handOffTo = KitRoute.GRAPHQL))
        val summaryJson = Json.encodeToString(GraphQLSummary(message = "One template is available: Devotional.", type = "DocumentTemplates"))
        val executor = getMockExecutor {
            mockLLMAnswer(routingJson) onRequestContains "Decide how to handle"
            mockLLMAnswer(chatJson) onRequestContains "Reply to the user"
            mockLLMToolCall(queryTool, GraphQLQueryTool.Input(query = "query { documentTemplates { all { name } } }")) onRequestContains "Handle this request against the Bosca GraphQL API"
            mockLLMAnswer(summaryJson) onRequestContains "Devotional"
        }

        val kit = KitAgent(bibleService, metadataService, mockk<DocumentService>(relaxed = true), mockk<CollectionService>(relaxed = true), sqlQuery, scriptServices, imageServices, graphql, executor, models, kitJson, InMemoryKitSessionService(), mockk(relaxed = true)).agent

        val response = withContext(KitToolContext(auth)) { kit.run(kitRequest("what content templates are available?")) }

        // route→CHAT, but chat hands off to GRAPHQL → the planner re-routes, the GraphQL agent looks it up,
        // and Kit answers with real data rather than a made-up template list.
        val gql = assertIs<KitResponse.GraphQL>(response, "a chat hand-off to GRAPHQL should end in a GraphQL response, got: $response")
        assertEquals("One template is available: Devotional.", gql.message)
        assertEquals(serverResult, gql.data)
        coVerify(exactly = 1) { graphql.execute(any(), any()) }
    }
}
