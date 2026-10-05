package bosca.analytics.routes

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.model.Event
import bosca.analytics.model.EventPipelineContext
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import bosca.analytics.model.QueryParameterType
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.analytics.service.EventProcessingService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.RequestHeaders
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsRoutesTest {
    private val application = BoscaApplication(ApplicationConfig.load("bosca: {}".byteInputStream()))
    private val authentication = mockk<AuthenticationContext>()

    private fun call(
        id: String? = null,
        path: String = "/api/v1/events",
        queryParameters: Parameters = Parameters.Empty,
        body: String = "",
    ): Pair<ServerCall, ServerResponse> {
        val request = mockk<ServerRequest>(relaxed = true)
        val response = mockk<ServerResponse>(relaxed = true)
        every { request.path } returns path
        every { request.queryParameters } returns queryParameters
        every { request.contentType() } returns null
        coEvery { request.bodyText() } returns body
        every { request.headers } returns mockk<RequestHeaders>(relaxed = true)
        return ServerCall(
            request,
            response,
            if (id == null) Parameters.Empty else Parameters.fromSingleValueMap(mapOf("id" to id)),
            application,
        ) to response
    }

    private fun query(id: UUID, refresh: Int? = null) = AnalyticsQuery(
        id = id,
        key = "query",
        name = "Query",
        description = "Query",
        query = "select 1",
        refreshIntervalSeconds = refresh,
    )

    private fun parameter(id: UUID, name: String, type: QueryParameterType) = AnalyticsQueryParameter(
        queryId = id,
        parameter = name,
        name = name,
        description = name,
        type = type,
        defaultValue = JsonNull,
        required = false,
        sort = 0,
    )

    @Test
    fun `POST execute verifies permission deserializes parameters and delegates`() = runTest {
        val id = UUID.random()
        val query = query(id)
        val queryService = mockk<AnalyticsQueryService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        val evaluator = mockk<AnalyticsQueryPermissionEvaluator>()
        val response = AnalyticsQueryResponse(listOf(JsonPrimitive(1)))
        val parameters = listOf(AnalyticsQueryExecutionParameterInput("limit", JsonPrimitive(1)))
        val route = Execute(queryService, execution, evaluator)
        val (call) = call(id.toString(), body = Json.encodeToString(ExecuteRequest.serializer(), ExecuteRequest(parameters)))
        coEvery { queryService.getQueryById(id) } returns query
        coEvery { evaluator.verifyAllowed(authentication, query, PermissionAction.EXECUTE) } returns Unit
        coEvery { execution.execute(id, any()) } returns response

        assertEquals(response, route.execute(call, authentication))
        assertEquals(AnalyticsQueryResponse.serializer().descriptor, route.serializer().descriptor)
        assertFailsWith<IllegalStateException> { route.execute(call().first, authentication) }
    }

    @Test
    fun `GET execute converts every scalar query parameter and ignores unknown names`() = runTest {
        val id = UUID.random()
        val query = query(id)
        val queryService = mockk<AnalyticsQueryService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        val evaluator = mockk<AnalyticsQueryPermissionEvaluator>()
        val response = AnalyticsQueryResponse(emptyList())
        val route = ExecuteGet(queryService, execution, evaluator)
        val definitions = listOf(
            parameter(id, "string", QueryParameterType.STRING),
            parameter(id, "integer", QueryParameterType.INTEGER),
            parameter(id, "float", QueryParameterType.FLOAT),
            parameter(id, "boolean", QueryParameterType.BOOLEAN),
            parameter(id, "date", QueryParameterType.DATE),
            parameter(id, "time", QueryParameterType.TIME),
            parameter(id, "datetime", QueryParameterType.DATETIME),
        )
        val values = Parameters(
            mapOf(
                "string" to listOf("value"),
                "integer" to listOf("2"),
                "float" to listOf("2.5"),
                "boolean" to listOf("true"),
                "date" to listOf("3"),
                "time" to listOf("4"),
                "datetime" to listOf("5"),
                "unknown" to listOf("ignored"),
            ),
        )
        val (call) = call(id.toString(), queryParameters = values)
        coEvery { queryService.getQueryById(id) } returns query
        coEvery { queryService.getParameters(id) } returns definitions
        coEvery { evaluator.verifyAllowed(authentication, query, PermissionAction.EXECUTE) } returns Unit
        coEvery { execution.execute(id, any()) } returns response

        assertEquals(response, route.execute(call, authentication))
        coVerify { execution.execute(id, match { it.size == 7 }) }
        assertEquals(AnalyticsQueryResponse.serializer().descriptor, route.serializer().descriptor)
    }

    @Test
    fun `GET execute rejects invalid numbers and unsupported structured types`() = runTest {
        val id = UUID.random()
        val query = query(id)
        val queryService = mockk<AnalyticsQueryService>()
        val execution = mockk<AnalyticsQueryExecutionService>()
        val evaluator = mockk<AnalyticsQueryPermissionEvaluator>()
        val route = ExecuteGet(queryService, execution, evaluator)
        coEvery { queryService.getQueryById(id) } returns query
        coEvery { evaluator.verifyAllowed(authentication, query, PermissionAction.EXECUTE) } returns Unit

        for (type in listOf(QueryParameterType.INTEGER, QueryParameterType.FLOAT, QueryParameterType.DATE, QueryParameterType.TIME, QueryParameterType.DATETIME)) {
            coEvery { queryService.getParameters(id) } returns listOf(parameter(id, "value", type))
            val (call) = call(id.toString(), queryParameters = Parameters(mapOf("value" to listOf("invalid"))))
            assertFailsWith<IllegalStateException> { route.execute(call, authentication) }
        }
        for (type in listOf(QueryParameterType.ARRAY, QueryParameterType.OBJECT, QueryParameterType.NONE)) {
            coEvery { queryService.getParameters(id) } returns listOf(parameter(id, "value", type))
            val (call) = call(id.toString(), queryParameters = Parameters(mapOf("value" to listOf("1"))))
            assertFailsWith<NotImplementedError> { route.execute(call, authentication) }
        }
        assertFailsWith<IllegalStateException> { route.execute(call().first, authentication) }
    }

    @Test
    fun `installation aliases queue a generated installation event`() = runTest {
        val processing = mockk<EventProcessingService>()
        coJustRun { processing.queue(any(), any()) }
        val routes = listOf(AddInstallation(processing), AddInstallationGet(processing), LegacyAddInstallation(processing), LegacyAddInstallation2(processing))

        for (route in routes) {
            val (call) = call()
            val installation = route.execute(call, authentication)
            assertTrue(installation.id.isNotBlank())
        }

        coVerify(exactly = 4) {
            processing.queue(any<EventPipelineContext>(), match { it.events.single().type == EventType.Installation })
        }
        assertEquals(bosca.analytics.installation.Installation.serializer().descriptor, routes.first().serializer().descriptor)
    }

    @Test
    fun `event routes queue bodies and select legacy versus modern status`() = runTest {
        val processing = mockk<EventProcessingService>()
        coJustRun { processing.queue(any(), any()) }
        val events = Events(events = listOf(Event(1, type = EventType.Session)), sent = 2, sentMicros = 3)
        val body = Json.encodeToString(Events.serializer(), events)
        val route = AddEvent(processing)
        val legacy = LegacyAddEvent(processing)
        val (modernCall, modernResponse) = call(path = "/api/v1/events", body = body)
        val (legacyCall, legacyResponse) = call(path = "/events", body = body)

        route.execute(modernCall, authentication)
        legacy.execute(legacyCall, authentication)

        verify { modernResponse.status(HttpStatusCode.Accepted) }
        verify { legacyResponse.status(HttpStatusCode.OK) }
        assertNull(route.serializer())
        assertNull(legacy.serializer())
    }

    @Test
    fun `flush route requires administrators and delegates`() = runTest {
        val processing = mockk<EventProcessingService>()
        val groups = mockk<GroupEvaluator>()
        coEvery { groups.verifyHasAdminGroup(authentication) } returns Unit
        coJustRun { processing.flush() }
        val route = Flush(processing, groups)
        val (call) = call()

        route.execute(call, authentication)

        coVerify { groups.verifyHasAdminGroup(authentication) }
        coVerify { processing.flush() }
        assertNull(route.serializer())
    }
}
