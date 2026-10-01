package bosca.ai.kit.agents.actions

import ai.koog.agents.core.agent.context.AIAgentPlannerContext
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.KitRoute
import bosca.ai.kit.agents.KitState
import bosca.ai.kit.tools.content.CreateDocumentTool
import bosca.content.metadata.service.MetadataService

/**
 * Persists the finished tiptap `Content` from the shared state as a `bosca/v-document`. This action
 * **owns** the [CreateDocumentTool] (which authorizes under the caller's identity) and contributes
 * the saved id + a user-facing `reply` — reaching the planner's goal for the WRITE path.
 */
class SaveDocumentAction(
    metadataService: MetadataService,
) : KitAction() {

    private val createDocument = CreateDocumentTool(metadataService)

    override val name: String = "save_document"

    override fun precondition(state: KitState): Boolean =
        state.route == KitRoute.WRITE && state.hasDocument && !state.responded

    override fun belief(state: KitState): KitState = state.copy(responded = true)

    override suspend fun execute(context: AIAgentPlannerContext, state: KitState, sessionId: String): KitState {
        val content = requireNotNull(state.content) { "save_document requires write_document to have produced Content first" }
        val result = createDocument.execute(CreateDocumentTool.Input(title = state.title.ifEmpty { "Untitled" }, content = content))
        return state.copy(
            responded = true,
            response = KitResponse.Document(
                metadataId = result.metadataId,
                message = "I wrote and saved the document (id ${result.metadataId}).",
            ),
        )
    }
}
