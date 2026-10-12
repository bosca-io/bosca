package bosca.graphql

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.db.ConnectionLimiter
import bosca.db.asCoroutineContext
import bosca.db.withConnectionManager
import bosca.di.provideBlockingNoSuspend
import bosca.graphql.dispatcher.CoreDispatchersRegistrar
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.dispatcher.DispatchersRegistry
import bosca.graphql.persistedqueries.PersistedQueryCacheImpl
import bosca.graphql.scalars.DateTime
import bosca.graphql.scalars.UUID
import bosca.graphql.scalars.Upload
import bosca.graphql.server.DataLoaderRegistry
import bosca.graphql.server.ExecutableSchema
import bosca.graphql.server.ExtendedScalars
import bosca.graphql.server.GraphQL
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.GraphQLException
import bosca.graphql.server.GraphQLRequest as EngineGraphQLRequest
import bosca.graphql.server.InMemoryPreparsedDocumentProvider
import bosca.graphql.server.Instrumentation
import bosca.graphql.server.MaxQueryComplexityInstrumentation
import bosca.graphql.server.MaxQueryDepthInstrumentation
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.security.service.AuthenticationContext
import bosca.security.service.AuthenticationProviders
import bosca.serialization.JsonContent
import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.telemetry.Tracing
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

@Serializable
data class GraphQLSubscriptionRequest(
    val type: String,
    val id: String? = null,
    val payload: JsonElement? = null,
)

@Serializable
data class GraphQLSubscriptionResponse(
    val id: String? = null,
    val type: String,
    val payload: JsonElement? = null,
)

@Serializable
data class GraphQLRequest(
    val query: String? = null,
    val extensions: JsonObject? = null,
    val operationName: String? = null,
    val variables: JsonObject? = null,
)

/** Aggregates the three GraphQL operation root objects passed to every execution. */
interface SchemaRoot {
    val query: QueryRoot
    val mutation: MutationRoot
    val subscription: SubscriptionRoot
}

interface QueryRoot
interface MutationRoot
interface SubscriptionRoot

abstract class GraphQLService(
    private val root: SchemaRoot,
    private val introspectionEnabled: Boolean,
    private val maxQueryDepth: Int = 50,
    private val maxQueryComplexity: Int = 1000,
) {

    private val mutex = Mutex()
    private var graphQL: GraphQL? = null
    private var executableSchema: ExecutableSchema? = null
    private val json by lazy { provideBlockingNoSuspend<Json>() }
    private val cacheManager by lazy { provideBlockingNoSuspend<CacheManager>() }
    private val requestCacheSerializer by lazy { provideBlockingNoSuspend<RequestCacheSerializer>() }
    private val tracer by lazy { provideBlockingNoSuspend<Tracer>() }
    private val exceptionHandler = ExceptionHandler { call ->
        call?.application?.errorCapture ?: application.errorCapture
    }
    private val authenticationProvider by lazy { provideBlockingNoSuspend<AuthenticationProviders>() }
    private val application by lazy { provideBlockingNoSuspend<BoscaApplication>() }
    private val persistedQueryCache by lazy { PersistedQueryCacheImpl() }
    protected open val dispatchersRegistrar: DispatchersRegistrar by lazy { CoreDispatchersRegistrar() }

    abstract suspend fun initialize(builder: RuntimeWiringBuilder)

    suspend fun warmup() {
        getGraphQL()
    }

    fun isReady(): Boolean = graphQL != null

    fun isIntrospectionEnabled(): Boolean = introspectionEnabled

    suspend fun getSchema() = getExecutableSchema().schema

    private suspend fun getExecutableSchema(): ExecutableSchema {
        getGraphQL()
        return executableSchema ?: error("GraphQL executable schema was not initialized")
    }

    private suspend fun getGraphQL(): GraphQL {
        graphQL?.let { return it }
        return mutex.withLock {
            graphQL?.let { return@withLock it }
            try {
                val builder = RuntimeWiringBuilder()
                    .apply {
                        scalar("DateTime", DateTime.Type)
                        scalar("JSON", ExtendedScalars.Json)
                        scalar("Long", ExtendedScalars.Long)
                        scalar("Upload", Upload.Type)
                        scalar("UUID", UUID.Type)
                    }

                DispatchersRegistry.register(builder, dispatchersRegistrar)
                initialize(builder)

                val executable = ExecutableSchema.from(SchemaRegistry.registry, builder.build())
                val engine = GraphQL(
                    executable = executable,
                    instrumentation = Instrumentation.of(
                        MaxQueryDepthInstrumentation(maxQueryDepth),
                        MaxQueryComplexityInstrumentation(maxQueryComplexity),
                    ),
                    preparsedDocumentProvider = InMemoryPreparsedDocumentProvider(),
                    exceptionHandler = exceptionHandler,
                )
                executableSchema = executable
                graphQL = engine
                engine
            } catch (e: Exception) {
                log.error("Failed to initialize GraphQL service: ${e.message}", e)
                throw e
            }
        }
    }

    private data class RequestContext(val context: GraphQLContext, val introspectionEnabled: Boolean)

    private fun requestContext(call: ServerCall, scope: CoroutineScope): RequestContext {
        val authentication = AuthenticationContext(call.authenticationContext, authenticationProvider)
        return requestContext(authentication, call.application, scope, call)
    }

    private fun requestContext(
        authentication: AuthenticationContext,
        requestApplication: BoscaApplication,
        scope: CoroutineScope,
        call: ServerCall? = null,
    ): RequestContext {
        val isAdmin = authentication.principal()?.hasGroup("administrators") ?: false
        val context = buildMap<String, Any?> {
            put("application", requestApplication)
            put("coroutineScope", scope)
            put("authenticationContext", authentication)
            call?.let { put("call", it) }
        }
        return RequestContext(
            GraphQLContext(context),
            introspectionEnabled && (requestApplication.developmentMode || isAdmin),
        )
    }

    private suspend fun resolveQuery(query: String?, extensions: JsonObject?): String {
        query?.let { return it }
        val persisted = extensions?.get("persistedQuery") as? JsonObject
        val sha256 = (persisted?.get("sha256Hash") as? JsonPrimitive)?.contentOrNull
            ?: throw GraphQLException("Must provide a query string")
        return persistedQueryCache.query(sha256)
            ?: throw GraphQLException(
                "PersistedQueryNotFound",
                mapOf("code" to JsonPrimitive("PERSISTED_QUERY_NOT_FOUND")),
            )
    }

    private fun dataLoaderRegistry(): DataLoaderRegistry =
        DataLoaderRegistry { dispatch -> withConnectionManager { dispatch() } }

    private suspend fun executeAsJsonElement(
        query: String?,
        operationName: String?,
        variables: Map<String, Any?>,
        extensions: JsonObject?,
        contextFactory: suspend (CoroutineScope) -> RequestContext,
    ): JsonElement = Tracing.withTrace {
        val span = tracer.spanBuilder("GraphQLService.execute").startSpan()
        try {
            val requestCache = RequestCache(cacheManager, requestCacheSerializer)
            val connectionLimiter = ConnectionLimiter()
            withContext(requestCache.asCoroutineContext() + span.asContextElement() + connectionLimiter.asCoroutineContext()) {
                val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
                try {
                    val requestContext = contextFactory(scope)
                    operationName?.let { span.setAttribute("graphql.operation", it) }
                    span.setAttribute("graphql.subscription", false)
                    log.debug("GraphQL Operation: {}", operationName)
                    getGraphQL().execute(
                        EngineGraphQLRequest(
                            query = resolveQuery(query, extensions),
                            operationName = operationName,
                            variables = variables,
                            rootValue = root,
                            context = requestContext.context,
                            dataLoaders = dataLoaderRegistry(),
                            introspectionEnabled = requestContext.introspectionEnabled,
                        ),
                    ).toJson()
                } finally {
                    scope.cancel()
                }
            }
        } catch (e: Exception) {
            log.error("Failed to execute GraphQL query: ${e.message}", e)
            bosca.graphql.server.ExecutionResult.ofErrors(listOf(exceptionHandler.handleException(e))).toJson()
        } finally {
            span.end()
        }
    }

    private suspend fun executeAsJsonElement(call: ServerCall, request: GraphQLRequest, variables: Map<String, Any?>): JsonElement =
        executeAsJsonElement(request.query, request.operationName, variables, request.extensions) { scope -> requestContext(call, scope) }

    private suspend fun execute(call: ServerCall, request: GraphQLRequest, variables: Map<String, Any?>): JsonContent<JsonElement> =
        JsonContent(tracer, json, executeAsJsonElement(call, request, variables), JsonElement.serializer())

    suspend fun get(call: ServerCall, query: String?, variables: JsonObject?, extensions: JsonObject?): JsonContent<JsonElement> =
        execute(call, GraphQLRequest(query = query, variables = variables, extensions = extensions), variables.orEmpty())

    suspend fun getAsJsonElement(
        call: ServerCall,
        operationName: String,
        query: String?,
        variables: JsonObject?,
        extensions: JsonObject?,
    ): JsonElement = executeAsJsonElement(
        call,
        GraphQLRequest(query, extensions, operationName, variables),
        variables.orEmpty(),
    )

    suspend fun post(call: ServerCall, request: GraphQLRequest): JsonContent<JsonElement> =
        execute(call, request, request.variables.orEmpty())

    suspend fun execute(authenticationContext: AuthenticationContext, request: GraphQLRequest): JsonElement =
        executeAsJsonElement(
            request.query,
            request.operationName,
            request.variables.orEmpty(),
            request.extensions,
        ) { scope -> requestContext(authenticationContext, application, scope) }

    suspend fun post(call: ServerCall, request: GraphQLRequest, variables: Map<String, Any?>): JsonContent<JsonElement> =
        execute(call, request, variables)

    suspend fun subscribe(call: ServerCall, request: GraphQLRequest): Flow<JsonElement> {
        val traceId = Tracing.currentTraceId() ?: Tracing.newTraceId()
        val query = try {
            resolveQuery(request.query, request.extensions)
        } catch (e: Exception) {
            return kotlinx.coroutines.flow.flowOf(Tracing.withTrace(traceId) {
                bosca.graphql.server.ExecutionResult.ofErrors(listOf(exceptionHandler.handleException(e))).toJson()
            })
        }
        return channelFlow {
            val span = tracer.spanBuilder("GraphQLService.execute").startSpan()
            try {
                val requestCache = RequestCache(cacheManager, requestCacheSerializer)
                val connectionLimiter = ConnectionLimiter()
                withContext(
                    Tracing.asCoroutineContext(traceId) +
                        requestCache.asCoroutineContext() +
                        span.asContextElement() +
                        connectionLimiter.asCoroutineContext(),
                ) {
                    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
                    try {
                        val requestContext = requestContext(call, scope)
                        request.operationName?.let { span.setAttribute("graphql.operation", it) }
                        span.setAttribute("graphql.subscription", true)
                        log.debug("GraphQL Subscription Operation: {}", request.operationName)
                        getGraphQL().executeSubscription(
                            EngineGraphQLRequest(
                                query = query,
                                operationName = request.operationName,
                                variables = request.variables.orEmpty(),
                                rootValue = root,
                                context = requestContext.context,
                                dataLoaders = dataLoaderRegistry(),
                                introspectionEnabled = requestContext.introspectionEnabled,
                            ),
                        ).collect { send(it.toJson()) }
                    } finally {
                        scope.cancel()
                    }
                }
            } catch (e: Exception) {
                log.error("Failed to execute GraphQL subscription query: ${e.message}", e)
                throw e
            } finally {
                span.end()
            }
        }.buffer(capacity = 0)
    }

    fun clearPersistedQueries() {
        persistedQueryCache.clear()
    }

    companion object {
        private val log = LoggerFactory.getLogger(GraphQLService::class.java)
    }
}
