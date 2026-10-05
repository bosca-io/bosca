package bosca.ai.kit.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.serialization.KotlinClassToken
import aws.smithy.kotlin.runtime.io.closeIfCloseable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The contract every Kit sub-agent implements: a specialist the planner delegates to, modeled as a
 * typed [Request] → [Response] capability. Sub-agents **exchange structured objects** — a finished
 * document, query data plus a suggested visualization, an edited image — never bare text. Each
 * implementation owns its own prompt, model, tools, and Koog strategy; the planner's actions hand it
 * a [Request] and fold its [Response] into [KitState].
 *
 * @param Request the structured input this sub-agent consumes.
 * @param Response the structured result this sub-agent produces.
 */
abstract class KitSubAgent<in Request, out Response> {

    private lateinit var agent: AIAgent<in Request, out Response>

    private val agentMutex = Mutex()
    protected abstract val service: GraphAIAgentService<in Request, out Response>

    protected suspend fun aiAgent(): AIAgent<in Request, out Response> {
        if (::agent.isInitialized) return agent
        return agentMutex.withLock {
            if (::agent.isInitialized) {
                return agent
            }
            agent = service.createAgent(id = service.agentConfig.prompt.id)
            agent
        }
    }

    /**
     * Run the sub-agent for [request] and return its structured [Response]. [sessionId] correlates this
     * run to the parent session Kit run (the actions pass `context.runId`) — which is the session id once the
     * top-level `agent.run(request, sessionId)` is invoked, so sub-agent runs join the same session.
     */
    suspend fun run(request: Request, sessionId: String): Response {
        val session = aiAgent().createSession(sessionId)
        return session.run(request)
    }
}
