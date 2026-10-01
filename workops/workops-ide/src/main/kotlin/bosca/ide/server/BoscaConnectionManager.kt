package bosca.ide.server

import bosca.graphql.client.GraphQLClient
import bosca.graphql.client.GraphQLClientException
import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.GraphQLSubscriptionClient
import bosca.graphql.client.KtorGraphQLClient
import bosca.graphql.client.KtorGraphQLSubscriptionClient
import bosca.ide.auth.BoscaCliAuthentication
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject as KotlinxJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

interface BoscaSubscription : AutoCloseable

/** Project-scoped lifecycle and IntelliJ async bridge for Bosca's shared GraphQL clients. */
@Service(Service.Level.PROJECT)
class BoscaConnectionManager(@Suppress("UNUSED_PARAMETER") project: Project) : Disposable {
    private val registry = BoscaServerRegistry.getInstance()
    private val authentication = BoscaCliAuthentication.getInstance()
    private val connections = ConcurrentHashMap<String, ManagedConnection>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(BoscaServerRegistry.PROFILES_CHANGED, BoscaServerRegistry.Listener { clearConnections() })
    }

    fun execute(
        profileId: String,
        document: String,
        variables: JsonObject = JsonObject(),
    ): CompletableFuture<JsonObject> = connection(profileId).execute(document, variables)

    fun subscribe(
        profileId: String,
        document: String,
        variables: JsonObject = JsonObject(),
        onNext: (JsonObject) -> Unit,
        onError: (Throwable) -> Unit,
        onComplete: () -> Unit = {},
    ): BoscaSubscription = connection(profileId).subscribe(document, variables, onNext, onError, onComplete)

    fun health(profileId: String): CompletableFuture<Boolean> =
        execute(profileId, "query BoscaIdeHealth { __typename }").thenApply { true }

    private fun connection(profileId: String): ManagedConnection {
        val profile = registry.profile(profileId)
            ?: throw IllegalArgumentException("Unknown Bosca server profile: $profileId")
        return connections.computeIfAbsent(profileId) { ManagedConnection(profile, authentication) }
    }

    private fun clearConnections() {
        connections.values.forEach(ManagedConnection::close)
        connections.clear()
    }

    override fun dispose() = clearConnections()

    companion object {
        fun test(profile: BoscaServerProfile): CompletableFuture<Boolean> {
            val connection = ManagedConnection(profile, BoscaCliAuthentication.getInstance())
            return connection.execute("query BoscaIdeHealth { __typename }", JsonObject())
                .thenApply { true }
                .whenComplete { _, _ -> connection.close() }
        }
    }

    private class ManagedConnection(
        private val profile: BoscaServerProfile,
        private val authentication: BoscaCliAuthentication,
    ) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val http = HttpClient(Java) { install(WebSockets) }.also { client ->
            installBoscaUnauthorizedRetry(client) { authentication.refresh(profile) }
        }
        private val client: GraphQLClient = KtorGraphQLClient(
            endpoint = profile.graphqlEndpoint,
            httpClient = http,
            headerProvider = {
                mapOf(HttpHeaders.Authorization to "Bearer ${authentication.token(profile)}")
            },
        )
        private val subscriptions: GraphQLSubscriptionClient = KtorGraphQLSubscriptionClient(
            endpoint = profile.webSocketEndpoint,
            httpClient = http,
            connectionPayloadProvider = {
                buildJsonObject {
                    put(HttpHeaders.Authorization, "Bearer ${authentication.token(profile)}")
                }
            },
        )

        fun execute(document: String, variables: JsonObject): CompletableFuture<JsonObject> = future {
            val response = client.execute(document, variables.toKotlinx(), operationName = null)
            response.errors?.takeIf { it.isNotEmpty() }?.let { throw GraphQLClientException(it) }
            val data = response.data
                ?: throw GraphQLClientException(listOf(bosca.graphql.client.GraphQLError("response contained no data")))
            JsonParser.parseString(data.toString()).asJsonObject
        }

        fun subscribe(
            document: String,
            variables: JsonObject,
            onNext: (JsonObject) -> Unit,
            onError: (Throwable) -> Unit,
            onComplete: () -> Unit,
        ): BoscaSubscription {
            val job = scope.launch {
                try {
                    subscriptions.subscribe(document, variables.toKotlinx(), operationName = null).collect { response ->
                        response.errors?.takeIf { it.isNotEmpty() }?.let { throw GraphQLClientException(it) }
                        val data = response.data
                            ?: throw GraphQLClientException(
                                listOf(bosca.graphql.client.GraphQLError("subscription response contained no data")),
                            )
                        onNext(JsonParser.parseString(data.toString()).asJsonObject)
                    }
                    onComplete()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onError(error)
                }
            }
            return JobSubscription(job)
        }

        private fun <T> future(block: suspend () -> T): CompletableFuture<T> {
            val future = CompletableFuture<T>()
            val job = scope.launch {
                try {
                    future.complete(block())
                } catch (error: CancellationException) {
                    future.cancel(false)
                    throw error
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
            future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
            return future
        }

        override fun close() {
            scope.cancel()
            http.close()
        }
    }

    private class JobSubscription(private val job: Job) : BoscaSubscription {
        override fun close() = job.cancel()
    }
}

private fun JsonObject.toKotlinx(): KotlinxJsonObject =
    GraphQLJson.parseToJsonElement(toString()).jsonObject

internal fun installBoscaUnauthorizedRetry(
    httpClient: HttpClient,
    refreshToken: suspend () -> String?,
) {
    httpClient.plugin(HttpSend).intercept { request ->
        val first = execute(request)
        if (first.response.status != HttpStatusCode.Unauthorized) return@intercept first

        val previous = request.headers[HttpHeaders.Authorization]
        val refreshed = try {
            refreshToken()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val authorization = refreshed?.let { "Bearer $it" }
        if (authorization == null || authorization == previous) return@intercept first

        request.headers.remove(HttpHeaders.Authorization)
        request.header(HttpHeaders.Authorization, authorization)
        execute(request)
    }
}
