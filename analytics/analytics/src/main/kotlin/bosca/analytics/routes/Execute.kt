package bosca.analytics.routes

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import bosca.analytics.model.QueryParameterType
import bosca.analytics.model.AnalyticsQueryResponse
import bosca.analytics.security.AnalyticsQueryPermissionEvaluator
import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.analytics.service.AnalyticsQueryService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class ExecuteRequest(val parameters: List<AnalyticsQueryExecutionParameterInput> = emptyList())

@RouteController("/api/v1/analytics/query/{id}/execute", RouteMethod.POST)
class Execute(
    private val queryService: AnalyticsQueryService,
    private val service: AnalyticsQueryExecutionService,
    private val queryPermissionEvaluator: AnalyticsQueryPermissionEvaluator
) : Route<AnalyticsQueryResponse>() {

    public override fun serializer(): KSerializer<AnalyticsQueryResponse> = AnalyticsQueryResponse.serializer()

    public override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): AnalyticsQueryResponse {
        val id = UUID.parse(call.pathParameters["id"] ?: error("Missing Query ID"))
        val query = queryService.getQueryById(id)
        queryPermissionEvaluator.verifyAllowed(authenticationContext, query, PermissionAction.EXECUTE)
        val request = call.receive<ExecuteRequest>()
        return service.execute(id, request.parameters)
    }
}

@RouteController("/api/v1/analytics/query/{id}/execute", RouteMethod.GET)
class ExecuteGet(
    private val queryService: AnalyticsQueryService,
    private val service: AnalyticsQueryExecutionService,
    private val queryPermissionEvaluator: AnalyticsQueryPermissionEvaluator
) : Route<AnalyticsQueryResponse>() {

    public override fun serializer(): KSerializer<AnalyticsQueryResponse> = AnalyticsQueryResponse.serializer()

    public override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): AnalyticsQueryResponse {
        val id = UUID.parse(call.pathParameters["id"] ?: error("Missing Query ID"))
        val query = queryService.getQueryById(id)
        queryPermissionEvaluator.verifyAllowed(authenticationContext, query, PermissionAction.EXECUTE)
        val parameters = mutableListOf<AnalyticsQueryExecutionParameterInput>()
        val queryParameters = queryService.getParameters(id).associateBy { it.parameter }
        for (name in call.request.queryParameters.names) {
            for (value in call.request.queryParameters.getAll(name)) {
                val parameter = queryParameters[name] ?: continue
                parameters.add(AnalyticsQueryExecutionParameterInput(
                    parameter = name,
                    value = JsonObject(mapOf("value" to when (parameter.type) {
                        QueryParameterType.STRING -> JsonPrimitive(value)
                        QueryParameterType.INTEGER -> JsonPrimitive(value.toIntOrNull() ?: error("Invalid integer value: $value"))
                        QueryParameterType.FLOAT -> JsonPrimitive(value.toFloatOrNull() ?: error("Invalid float value: $value"))
                        QueryParameterType.BOOLEAN -> JsonPrimitive(value.toBoolean())
                        QueryParameterType.DATE -> JsonPrimitive(value.toLongOrNull() ?: error("Invalid date value: $value"))
                        QueryParameterType.TIME -> JsonPrimitive(value.toLongOrNull() ?: error("Invalid date value: $value"))
                        QueryParameterType.DATETIME -> JsonPrimitive(value.toLongOrNull() ?: error("Invalid date value: $value"))
                        QueryParameterType.ARRAY -> TODO()
                        QueryParameterType.OBJECT -> TODO()
                        QueryParameterType.NONE -> TODO()
                    }))
                ))
            }
        }
        return service.execute(id, parameters)
    }
}
