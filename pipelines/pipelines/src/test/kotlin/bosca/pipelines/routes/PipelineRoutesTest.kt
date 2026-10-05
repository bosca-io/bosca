package bosca.pipelines.routes

import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.node.InputNode
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for the pipeline REST route handlers — [ExecutePipelinePost] and [ExecutePipelineGet] —
 * driving every branch of the shared `resolvePipeline` / `executePipeline` helpers (key/api/public
 * gating, schema validation, OK/SUSPENDED/failed outcomes).
 */
class PipelineRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val pipelineService = mockk<PipelineService>()
    private val permissionEvaluator = mockk<PipelinePermissionEvaluator>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val authenticationContext = mockk<AuthenticationContext>(relaxed = true)

    private val pipelineId = UUID.parse("00000000-0000-0000-0000-0000000000aa")
    private val runId = UUID.parse("00000000-0000-0000-0000-0000000000bb")

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private fun jsonPipeline(
        api: Boolean = true,
        public: Boolean = true,
        schema: JsonElement? = null,
        key: String = "my-key",
    ): Pipeline = Pipeline(
        id = pipelineId,
        name = "p",
        acceptedInputType = InputNode.JSON_TYPE,
        api = api,
        public = public,
        key = key,
        nodes = listOf(
            InputNode(id = "in", acceptedType = InputNode.JSON_TYPE, schema = schema),
        ),
    )

    private fun outcome(
        status: PipelineRunStatus,
        output: JsonElement? = null,
        error: String? = null,
    ): PipelineRun = PipelineRun(
        id = runId,
        pipelineId = pipelineId,
        status = status,
        graphSnapshot = JsonObject(emptyMap()),
        output = output,
        error = error,
    )

    /** A ServerCall whose POST body deserializes to [body] via the inline `receive<JsonElement>()`. */
    private fun postCall(
        pathParams: Map<String, String> = mapOf("key" to "my-key"),
        body: String = "{}",
        contentType: ContentType? = null,
    ): ServerCall {
        val app = mockk<BoscaApplication>()
        every { app.json } returns json
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.contentType() } returns contentType
        coEvery { request.bodyText() } returns body
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.application } returns app
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    /** A ServerCall exposing only query parameters (for the GET handler). */
    private fun getCall(
        pathParams: Map<String, String> = mapOf("key" to "my-key"),
        queryParams: Map<String, List<String>> = mapOf("a" to listOf("1"), "b" to listOf("x", "y")),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams)
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    /**
     * The route handlers' `execute(call, auth)` is `protected` (inherited from [bosca.routes.Route]).
     * Rather than reflect into it (which would need kotlin-reflect — the platform is native/reflection-free),
     * each route is subclassed once to expose it; the subclass can call its inherited protected member.
     */
    private interface RouteUnderTest {
        suspend fun invoke(call: ServerCall, auth: AuthenticationContext): JsonElement
    }

    private class TestablePost(
        ps: PipelineService, pe: PipelinePermissionEvaluator, rs: PipelineRunService, j: Json,
    ) : ExecutePipelinePost(ps, pe, rs, j), RouteUnderTest {
        override suspend fun invoke(call: ServerCall, auth: AuthenticationContext) = execute(call, auth)
    }

    private class TestableGet(
        ps: PipelineService, pe: PipelinePermissionEvaluator, rs: PipelineRunService, j: Json,
    ) : ExecutePipelineGet(ps, pe, rs, j), RouteUnderTest {
        override suspend fun invoke(call: ServerCall, auth: AuthenticationContext) = execute(call, auth)
    }

    private suspend fun invokeExecute(route: RouteUnderTest, call: ServerCall, auth: AuthenticationContext): JsonElement =
        route.invoke(call, auth)

    private fun postRoute() = TestablePost(pipelineService, permissionEvaluator, runService, json)
    private fun getRoute() = TestableGet(pipelineService, permissionEvaluator, runService, json)

    // ---------------------------------------------------------------------------------------------
    // ExecutePipelinePost — happy paths and outcome mapping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `POST public pipeline returns the OK output and skips permission check`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline(public = true)
        val out = buildJsonObject { put("answer", 42) }
        coEvery { runService.start(any(), any(), eq("api"), any(), any()) } returns outcome(PipelineRunStatus.OK, output = out)

        val result = invokeExecute(postRoute(), postCall(body = """{"x":1}"""), authenticationContext)

        assertEquals(out, result)
        coVerify(exactly = 0) { permissionEvaluator.verifyAllowed(any(), any(), any()) }
        coVerify { runService.start(any(), any(), eq("api"), any(), eq(authenticationContext)) }
    }

    @Test
    fun `POST OK with null output returns JsonNull`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline()
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns outcome(PipelineRunStatus.OK, output = null)

        val result = invokeExecute(postRoute(), postCall(), authenticationContext)

        assertEquals(JsonNull, result)
    }

    @Test
    fun `POST SUSPENDED returns a run handle object`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline()
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns outcome(PipelineRunStatus.SUSPENDED)

        val result = invokeExecute(postRoute(), postCall(), authenticationContext)

        val obj = result.jsonObject
        assertEquals(runId.toString(), obj["runId"]!!.jsonPrimitive.content)
        assertEquals("SUSPENDED", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST reports admission shedding when the pipeline is at its run cap`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline()
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns null

        val ex = assertFailsWith<IllegalStateException> {
            invokeExecute(postRoute(), postCall(), authenticationContext)
        }

        assertTrue(ex.message.orEmpty().contains("my-key"), "message was: ${ex.message}")
        assertTrue(ex.message.orEmpty().contains("concurrency or rate cap"), "message was: ${ex.message}")
    }

    @Test
    fun `POST failed outcome throws with the error message`() = runTest {
        coEvery { pipelineService.getByKey("broken-key") } returns jsonPipeline(key = "broken-key")
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns
            outcome(PipelineRunStatus.FAILED, error = "boom")

        val ex = assertFailsWith<IllegalStateException> {
            invokeExecute(postRoute(), postCall(pathParams = mapOf("key" to "broken-key")), authenticationContext)
        }
        assertTrue(ex.message!!.contains("failed: boom"), "message was: ${ex.message}")
        assertTrue(ex.message!!.contains("broken-key"), "message was: ${ex.message}")
    }

    @Test
    fun `POST cancelled outcome also takes the else error arm`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline()
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns
            outcome(PipelineRunStatus.CANCELLED, error = null)

        val ex = assertFailsWith<IllegalStateException> {
            invokeExecute(postRoute(), postCall(), authenticationContext)
        }
        assertTrue(ex.message!!.contains("failed:"), "message was: ${ex.message}")
    }

    // ---------------------------------------------------------------------------------------------
    // resolvePipeline branches (driven through the POST handler)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `POST missing key path parameter throws IllegalStateException`() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            invokeExecute(postRoute(), postCall(pathParams = emptyMap()), authenticationContext)
        }
        assertTrue(ex.message!!.contains("Missing pipeline key"), "message was: ${ex.message}")
    }

    @Test
    fun `POST unknown key throws NoSuchElementException`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns null

        val ex = assertFailsWith<NoSuchElementException> {
            invokeExecute(postRoute(), postCall(), authenticationContext)
        }
        assertTrue(ex.message!!.contains("Pipeline not found: my-key"), "message was: ${ex.message}")
    }

    @Test
    fun `POST non-api pipeline is indistinguishable from not found`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline(api = false)

        val ex = assertFailsWith<NoSuchElementException> {
            invokeExecute(postRoute(), postCall(), authenticationContext)
        }
        assertTrue(ex.message!!.contains("Pipeline not found: my-key"), "message was: ${ex.message}")
    }

    @Test
    fun `POST non-public pipeline runs permission check and proceeds when allowed`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline(public = false)
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns outcome(PipelineRunStatus.OK, output = JsonNull)

        invokeExecute(postRoute(), postCall(), authenticationContext)

        coVerify {
            permissionEvaluator.verifyAllowed(authenticationContext, any<Pipeline>(), PermissionAction.EXECUTE)
        }
    }

    @Test
    fun `POST non-public pipeline propagates an unauthorized permission failure`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline(public = false)
        coEvery { permissionEvaluator.verifyAllowed(any(), any(), any()) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            invokeExecute(postRoute(), postCall(), authenticationContext)
        }
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // Schema validation (resolvePipelineRunInput, through executePipeline)
    // ---------------------------------------------------------------------------------------------

    private val requiringSchema: JsonElement = Json.parseToJsonElement(
        """{"type":"object","required":["email"],"properties":{"email":{"type":"string"}}}""",
    )

    @Test
    fun `POST schema-valid payload passes validation and runs`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline(schema = requiringSchema)
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns outcome(PipelineRunStatus.OK, output = JsonNull)

        val result = invokeExecute(postRoute(), postCall(body = """{"email":"a@b.io"}"""), authenticationContext)

        assertEquals(JsonNull, result)
        coVerify { runService.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `POST schema-invalid payload throws before running`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline(schema = requiringSchema)

        val ex = assertFailsWith<IllegalStateException> {
            invokeExecute(postRoute(), postCall(body = """{}"""), authenticationContext)
        }
        assertTrue(ex.message!!.contains("does not match"), "message was: ${ex.message}")
        coVerify(exactly = 0) { runService.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `POST JSON pipeline with no schema skips validation`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline(schema = null)
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns outcome(PipelineRunStatus.OK, output = JsonNull)

        invokeExecute(postRoute(), postCall(body = """{"anything":true}"""), authenticationContext)

        coVerify { runService.start(any(), any(), any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------------------
    // ExecutePipelineGet — query params become the JSON input
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `GET converts query parameters to a JSON object input and returns OK output`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline()
        val out = JsonPrimitive("done")
        coEvery {
            runService.start(any(), any(), any(), any(), any())
        } returns outcome(PipelineRunStatus.OK, output = out)

        val result = invokeExecute(getRoute(), getCall(), authenticationContext)

        assertEquals(out, result)
        coVerify { runService.start(any(), any(), eq("api"), any(), eq(authenticationContext)) }
    }

    @Test
    fun `GET empty query parameters still resolves and runs`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns jsonPipeline()
        coEvery { runService.start(any(), any(), any(), any(), any()) } returns outcome(PipelineRunStatus.OK, output = JsonNull)

        val result = invokeExecute(getRoute(), getCall(queryParams = emptyMap()), authenticationContext)

        assertEquals(JsonNull, result)
    }

    @Test
    fun `GET unknown key throws NoSuchElementException`() = runTest {
        coEvery { pipelineService.getByKey("my-key") } returns null

        assertFailsWith<NoSuchElementException> {
            invokeExecute(getRoute(), getCall(), authenticationContext)
        }
    }

}
