package bosca.ai.kit.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgentService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A direct structured Koog capability used by metadata enrichment. Each implementation owns its
 * prompt, model and strategy, and returns a typed response to the owning service.
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
     * Runs [request] in the explicitly identified Koog session and returns its structured response.
     */
    suspend fun run(request: Request, sessionId: String): Response {
        val session = aiAgent().createSession(sessionId)
        return session.run(request)
    }
}
