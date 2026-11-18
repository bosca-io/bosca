package bosca.cli.a2a

import ai.koog.a2a.model.AgentCard
import ai.koog.a2a.model.Event
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.Response
import ai.koog.a2a.transport.ServerCallContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

/** Keeps A2A event subscription setup ordered with agent execution. */
internal class KitA2AServer(
    executor: AgentExecutor,
    agentCard: AgentCard,
    coroutineContext: CoroutineContext,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : A2AServer(
    agentExecutor = executor,
    agentCard = agentCard,
    coroutineScope = CoroutineScope(coroutineContext + dispatcher),
) {
    // A2AServer and SessionManager signal readiness in onStart, before their hot-flow
    // subscriptions exist. Sharing one dispatcher lane lets subscription setup finish
    // before the resumed executor can publish an event. Suspended turns still overlap.
    override fun onSendMessageCommon(
        request: Request<MessageSendParams>,
        ctx: ServerCallContext,
    ): Flow<Response<Event>> = super.onSendMessageCommon(request, ctx).flowOn(dispatcher)
}
