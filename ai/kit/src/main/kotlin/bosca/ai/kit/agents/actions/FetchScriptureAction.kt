package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.content.metadata.service.BibleService

/**
 * Fetches the Scripture the writer will quote — as structured [bosca.content.metadata.model.BibleChapter]
 * objects from [BibleService], contributed into the shared state's `scripture`. The LLM is never the
 * source of Bible content; it only chose the references (in [RouteAction]).
 */
class FetchScriptureAction(
    private val bibleService: BibleService,
) : KitAction() {

    override val name: String = "fetch_scripture"

    override fun precondition(state: KitState): Boolean =
        state.route == KitRoute.WRITE && state.references.isNotEmpty() && !state.hasScripture

    override fun belief(state: KitState): KitState = state.copy(hasScripture = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val source = bibleService.getBibles().first()
        val chapters = state.references.flatMap { human ->
            bibleService.getReferences(source, human).map { reference -> bibleService.getChapter(source, reference) }
        }
        return state.copy(hasScripture = true, scripture = chapters)
    }
}
