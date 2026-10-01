package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.planner.goap.GOAPPlannerBuilder
import ai.koog.serialization.KotlinClassToken
import ai.koog.serialization.typeToken
import bosca.ai.kit.agents.KitRequest
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.session.KitSessionContext
import bosca.serialization.UUID
import kotlinx.coroutines.withContext

/**
 * One self-contained unit of Kit's planning. A [KitAction] is **aware that it is part of the Kit
 * agent**: it reads the shared [KitState] in [precondition], predicts its effect in [belief] (for
 * A* planning), and **contributes** to the state in [execute] by returning the next [KitState].
 *
 * An action also **manages its own internals** — its prompt, its config, and any sub-agent it
 * drives (e.g. an analytics action owns its SQL sub-agent and decides what to hand it). It is
 * allocated with whatever it needs and then **registers itself onto the planner** via [action], so
 * Kit grows by allocating a class rather than by editing one monolithic planner block.
 */
abstract class KitAction {

    /** Stable action name the planner uses in plans. */
    protected abstract val name: String

    /** True when this action is applicable in [state]. */
    protected abstract fun precondition(state: KitState): Boolean

    /** The state the planner should PREDICT after this action (for planning, not real execution). */
    protected abstract fun belief(state: KitState): KitState

    /** A* step cost; override to bias the planner toward/away from this action. */
    protected open fun cost(state: KitState): Double = 1.0

    /** Do the real work for [state] and return the contributed next [KitState]. */
    protected abstract suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState

    private val sessionIdKey by lazy { createStorageKey<String>("subagent:$name:sessionId", typeToken<String>()) }

    /** Register this action onto the GOAP planner builder (the receiver). */
    fun GOAPPlannerBuilder<KitRequest, KitResponse, KitState>.action() {
        action(
            name = name,
            precondition = { state -> precondition(state) },
            belief = { state -> belief(state) },
            cost = { state -> cost(state) },
            execute = { context, state ->
                val sessionId = context.storage.get(sessionIdKey) ?: UUID.random().toString().also {
                    context.storage.set(sessionIdKey, it)
                }
                // Bind the action (and any sub-agent it drives) to THIS chat session, so a sub-agent
                // run's checkpoints — keyed by their own UUID runId — are indexed under the parent session.
                val parentSessionId = UUID.parse(context.runId)
                withContext(KitSessionContext(parentSessionId)) {
                    execute(context, state, sessionId)
                }
            },
        )
    }
}
