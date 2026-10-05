package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.writer.WriterAgent
import bosca.ai.kit.agents.writer.WriterRequest
import bosca.ai.kit.configuration.KitJson
import bosca.content.metadata.service.BibleService
import kotlinx.serialization.json.Json

/**
 * Writes the document. This action **owns** the [WriterAgent] sub-agent and decides what to hand it
 * (the task plus the Scripture already in the shared state). The writer may answer in two ways:
 *
 * - it produced the document → contribute the structured tiptap `Content`, or
 * - it needs Scripture it wasn't given → record the [WriterResponse.references] (without producing a
 *   document) so the planner runs `fetch_scripture` and comes back here with the chapters in hand.
 *
 * The `!state.hasScripture` guard means a writer that keeps asking after we've already fetched is
 * forced to write, so the WRITE path can't loop.
 */
class WriteDocumentAction(
    promptExecutor: PromptExecutor,
    model: LLModel,
    bibleService: BibleService,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitAction() {

    private val writer = WriterAgent(promptExecutor, model, bibleService, json, sessionService)

    override val name: String = "write_document"

    override fun precondition(state: KitState): Boolean =
        state.route == KitRoute.WRITE && (state.references.isEmpty() || state.hasScripture) && !state.hasDocument

    override fun belief(state: KitState): KitState = state.copy(hasDocument = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val response = writer.run(WriterRequest(task = state.request.message, scripture = state.scripture), sessionId)
        return if (response.needsScripture && !state.hasScripture) {
            state.copy(references = response.references)
        } else {
            state.copy(hasDocument = true, content = response.content)
        }
    }
}
