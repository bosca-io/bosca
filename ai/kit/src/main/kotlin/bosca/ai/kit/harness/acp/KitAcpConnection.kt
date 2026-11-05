package bosca.ai.kit.harness.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AuthMethodId
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.EOFException
import java.util.concurrent.atomic.AtomicReference

/** One outbound ACP connection. Reconnection never submits a previously sent prompt. */
class KitAcpConnection private constructor(
    private val protocol: Protocol,
    private val transport: Transport,
    private val client: Client,
    val agentInfo: AgentInfo,
    private val failure: AtomicReference<Throwable?>,
) : AutoCloseable {
    /**
     * Restores only capabilities advertised by this connection. A reconstructed session needs current,
     * authorized canonical context in its next prompt; callers must record that continuity boundary.
     * A failed advertised restore is propagated, never silently changed into a new session.
     */
    @OptIn(UnstableApi::class)
    suspend fun openSession(
        parameters: SessionCreationParameters,
        operations: ClientOperationsFactory,
        previousSessionId: String? = null,
        timeoutMillis: Long = 30_000,
    ): AcpSessionSelection {
        require(timeoutMillis > 0)
        parameters.mcpServers.forEach { server ->
            when (server) {
                is McpServer.Http -> require(agentInfo.capabilities.mcpCapabilities.http) { "Runtime does not support HTTP MCP connections" }
                is McpServer.Sse -> require(agentInfo.capabilities.mcpCapabilities.sse) { "Runtime does not support SSE MCP connections" }
                is McpServer.Stdio -> Unit
            }
        }
        return exchange(failure) {
            withTimeout(timeoutMillis) {
                if (previousSessionId == null) {
                    AcpSessionSelection(client.newSession(parameters, operations), AcpSessionContinuity.NEW)
                } else {
                    require(previousSessionId.isNotBlank()) { "ACP session id must not be blank" }
                    val id = SessionId(previousSessionId)
                    when {
                        agentInfo.capabilities.sessionCapabilities.resume != null ->
                            AcpSessionSelection(client.resumeSession(id, parameters, operations), AcpSessionContinuity.RESUMED)
                        agentInfo.capabilities.loadSession ->
                            AcpSessionSelection(client.loadSession(id, parameters, operations), AcpSessionContinuity.LOADED)
                        else -> AcpSessionSelection(client.newSession(parameters, operations), AcpSessionContinuity.RECONSTRUCTED)
                    }
                }
            }
        }
    }

    /** Streams one prompt; transport loss remains a failure and never resubmits the prompt. */
    fun prompt(session: ClientSession, content: List<ContentBlock>, correlation: JsonObject): Flow<Event> {
        require(session.client === client) { "ACP session belongs to a different connection" }
        return flow { emitAll(session.prompt(content, correlation)) }.catch { cause ->
            throw connectionFailure(cause, failure)
        }
    }

    /** Disconnects without closing or deleting the harness's durable ACP session. */
    override fun close() {
        protocol.close()
        transport.close()
    }

    companion object {
        /**
         * Negotiates ACP v1 with no Runner filesystem/terminal callbacks. Provider authentication uses
         * an advertised method selected by deployment configuration; credentials are not ACP metadata.
         */
        @OptIn(UnstableApi::class)
        suspend fun connect(
            scope: CoroutineScope,
            factory: AcpTransportFactory,
            authenticationMethod: String? = null,
            initializeTimeoutMillis: Long = 30_000,
        ): KitAcpConnection {
            require(initializeTimeoutMillis > 0)
            val transport = withTimeout(initializeTimeoutMillis) { factory.connect(scope) }
            val protocol = Protocol(scope, transport)
            val failure = AtomicReference<Throwable?>()
            transport.onClose {
                failure.compareAndSet(null, EOFException("ACP transport closed"))
                protocol.close()
            }
            transport.onError {
                failure.compareAndSet(null, it)
                protocol.close()
            }
            var initialized = false
            try {
                val client = Client(protocol)
                protocol.rejectHostCallbacks()
                protocol.start()
                val info = exchange(failure) {
                    withTimeout(initializeTimeoutMillis) {
                        client.initialize(ClientInfo(
                            protocolVersion = 1,
                            capabilities = ClientCapabilities(),
                            implementation = Implementation("bosca-runner", "1"),
                        )).also { agent ->
                            check(agent.protocolVersion == 1) { "Runtime negotiated an unsupported ACP version" }
                            if (authenticationMethod != null) {
                                val method = AuthMethodId(authenticationMethod)
                                require(agent.authMethods.any { it.id == method }) { "Runtime does not advertise the configured authentication method" }
                                client.authenticate(method)
                            }
                        }
                    }
                }
                initialized = true
                return KitAcpConnection(protocol, transport, client, info, failure)
            } finally {
                if (!initialized) {
                    protocol.close()
                    transport.close()
                }
            }
        }

        private suspend fun <T> exchange(failure: AtomicReference<Throwable?>, block: suspend () -> T): T = try {
            block()
        } catch (cancelled: CancellationException) {
            throw connectionFailure(cancelled, failure)
        }

        private suspend fun connectionFailure(cause: Throwable, failure: AtomicReference<Throwable?>): Throwable {
            currentCoroutineContext().ensureActive()
            return if (cause is CancellationException) failure.get()?.let(::AcpConnectionException) ?: cause else cause
        }
    }
}
