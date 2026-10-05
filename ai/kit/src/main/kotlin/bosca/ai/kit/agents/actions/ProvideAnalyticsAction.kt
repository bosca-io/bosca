package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.analytics.AnalyticsAgent
import bosca.ai.kit.agents.analytics.AnalyticsRequest
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.sql.SqlQuery
import bosca.ai.kit.tools.analytics.InvestigationRecorder
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Provides an analytics answer for a data question. This action **owns** the [AnalyticsAgent] sub-agent
 * (which runs its own tool loop over the SQL tools) and decides what to hand it; it folds the
 * sub-agent's structured [bosca.ai.kit.agents.analytics.AnalyticsResponse] (summary + data + suggested
 * visualization) into a [KitResponse.Analytics] so the data survives to Kit's output.
 */
class ProvideAnalyticsAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    sqlQuery: SqlQuery,
    json: KitJson,
    sessionService: KitSessionService?,
    analyticsServices: AnalyticsServices? = null,
) : KitAction() {

    private val sqlAgent = AnalyticsAgent(promptExecutor, model, sqlQuery, json, sessionService, analyticsServices)

    override val name: String = "provide_analytics"

    override fun precondition(state: KitState): Boolean = state.route == KitRoute.QUERY && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        // Record the SQL the sub-agent's tool loop ACTUALLY executes, so the answer is sourced by the
        // statement that ran — not the model's recollection of it. The model's claimed query picks
        // WHICH executed statement sourced the answer (the loop may run sanity probes afterwards),
        // but the text shown is the executed one. Last-executed covers a paraphrased claim, and the
        // claim alone covers a run that executed nothing (e.g. resumed from a checkpoint).
        val recorder = InvestigationRecorder()
        val answer = withContext(recorder) {
            sqlAgent.run(AnalyticsRequest(question = state.request.message), sessionId)
        }
        return state.copy(
            responded = true,
            response = KitResponse.Analytics(
                summary = answer.summary,
                query = recorder.findExecuted(answer.query) ?: recorder.lastQuery ?: answer.query,
                columns = answer.columns,
                rows = answer.rows,
                visualization = answer.visualization,
                savedQueryId = answer.savedQueryId,
                savedQueryKey = answer.savedQueryKey,
                visualizationId = answer.visualizationId,
                dashboardId = answer.dashboardId,
                investigation = recorder.assemble(answer.annotations),
            ),
        )
    }
}
