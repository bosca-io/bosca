package bosca.configuration

import bosca.di.provide
import bosca.di.provideProvider
import bosca.ext.jsonObjectToMap
import bosca.graphql.GraphQLConnectionInitAuthenticator
import bosca.graphql.GraphQLRegistrar
import bosca.graphql.GraphQLRequest
import bosca.graphql.GraphQLService
import bosca.graphql.GraphQLWebSocketProtocolHandler
import bosca.graphql.scalars.UploadedFile
import bosca.security.service.AuthenticationProviders
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.content.PartData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Configures the GraphQL subsystem by registering all type controllers and data
 * fetchers with the [GraphQLService], setting up HTTP and WebSocket endpoints for
 * queries, mutations, and subscriptions, and warming up the schema in the background.
 */
class GraphQLModule(private val service: GraphQLService) : BoscaApplicationModule {

    companion object {
        /**
         * Sets a value at a dot-separated path within a mutable variables map.
         * Paths follow the GraphQL multipart request spec format: "variables.file"
         * or "variables.input.avatar" for nested objects.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun setAtPath(variables: MutableMap<String, Any?>, path: String, value: Any) {
            val parts = path.split(".")
            if (parts.firstOrNull() != "variables" || parts.size < 2) return
            var current: MutableMap<String, Any?> = variables
            for (i in 1 until parts.size - 1) {
                val next = current[parts[i]]
                if (next !is Map<*, *>) return
                val copy = LinkedHashMap<String, Any?>(next as Map<String, Any?>)
                current[parts[i]] = copy
                current = copy
            }
            current[parts.last()] = value
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun install(application: BoscaApplication): Unit = with(application) {
        GraphQLRegistrar.register(service)

        val providers = provide<AuthenticationProviders>()
        // Optional — registered by the security module's AuthenticationModule.
        // Applications composed without it keep upgrade-request-only auth.
        val connectionInitAuthenticator = provideProvider<GraphQLConnectionInitAuthenticator>()
            .takeIf { it.exists }?.get()
        val json = Json(provide<Json>()) {
            ignoreUnknownKeys = true
        }
        routing {
            authenticate(*providers.providers, optional = true) {
                get("/graphql") {
                    val query = call.request.queryParameters["query"]
                    val variables = call.request.queryParameters["variables"]?.let { json.decodeFromString(JsonObject.serializer(), it) }
                    val extensions = call.request.queryParameters["extensions"]?.let { json.decodeFromString(JsonObject.serializer(), it) }

                    if (query.isNullOrEmpty()) {
                        val request = try {
                            call.receive<GraphQLRequest>()
                        } catch (e: Exception) {
                            GraphQLRequest(
                                extensions = extensions,
                                variables = variables,
                            )
                        }
                        val content = service.post(call, request)
                        call.respond(content)
                    } else {
                        val content = service.get(call, query, variables, extensions)
                        call.respond(content)
                    }
                }
                post("/graphql") {
                    val ct = call.request.contentType()
                    if (ct != null && ct.match("multipart/form-data")) {
                        val multipart = call.receiveMultipart()
                        var operationsJson: String? = null
                        var mapJson: String? = null
                        val files = mutableMapOf<String, PartData.FileItem>()
                        for (part in multipart) {
                            when (part) {
                                is PartData.FormItem -> when (part.name) {
                                    "operations" -> operationsJson = part.value
                                    "map" -> mapJson = part.value
                                }
                                is PartData.FileUploadItem -> part.name?.let { files[it] = part }
                                else -> {}
                            }
                        }
                        requireNotNull(operationsJson) { "Missing 'operations' field in multipart GraphQL request" }
                        val request = json.decodeFromString<GraphQLRequest>(operationsJson)
                        val variables = request.variables?.let { jsonObjectToMap(it) }?.toMutableMap() ?: mutableMapOf()
                        if (mapJson != null) {
                            val fileMap = json.decodeFromString<Map<String, List<String>>>(mapJson)
                            for ((fileKey, paths) in fileMap) {
                                val file = files[fileKey] ?: continue
                                val uploadedFile = UploadedFile(file.originalFileName, file.contentType, file.streamProvider)
                                for (path in paths) {
                                    setAtPath(variables, path, uploadedFile)
                                }
                            }
                        }
                        val content = service.post(call, request, variables)
                        call.respond(content)
                    } else {
                        val request = call.receive<GraphQLRequest>()
                        val content = service.post(call, request)
                        call.respond(content)
                    }
                }
                webSocket("/graphqlws", "graphql-transport-ws") {
                    GraphQLWebSocketProtocolHandler.handle(
                        this,
                        json,
                        service::subscribe,
                        // Browsers can't set an Authorization header on a
                        // WebSocket upgrade, so SPA clients pass their token
                        // via the connection_init payload — without this hook
                        // those subscriptions execute anonymously and fail
                        // their resolvers' authorization checks.
                        onConnectionInit = connectionInitAuthenticator?.let { authenticator ->
                            { call, payload -> authenticator.authenticate(call, payload) }
                        },
                    )
                }
            }
            get("/graphiql") {
                if (service.isIntrospectionEnabled()) {
                    javaClass.getResourceAsStream("/graphiql/index.html").use { resource ->
                        resource?.let { call.respondBytes(it.readAllBytes(), ContentType.Text.Html) } ?: call.respond(HttpStatusCode.NotFound, "")
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound, "")
                }
            }
        }

        application.launch {
            service.warmup()
        }
    }
}
