package bosca.ai.prompts.service

import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import bosca.service.Service
import kotlinx.serialization.json.JsonObject

/**
 * SPI for rendering a [bosca.ai.prompts.model.Prompt] with arguments and running it through a
 * [bosca.ai.models.model.Model] to produce a reply.
 *
 * The capability lives in the `kit` implementation (Google ADK). This contract lets lean
 * consumers — notably the `mcp/` bridge dispatching prompt+model-backed AgentTools — invoke it
 * without depending on the implementation module.
 *
 * [call] and [authentication] identify the calling principal so the run is attributed to the
 * actual caller (matching the chat path), not a placeholder.
 *
 * Resolve it optionally (via `ObjectProvider`): if no implementation is registered, prompt+model
 * dispatch is simply unavailable rather than a hard failure.
 */
interface PromptModelExecutor : Service {
    /**
     * Render the prompt identified by [promptId] using [arguments] and run it through the model
     * identified by [modelId], returning the model's reply text.
     *
     * @param call the originating server call
     * @param authentication the calling principal's authentication context
     * @param promptId the prompt template to render
     * @param modelId the model to run the rendered prompt through
     * @param arguments the caller-supplied arguments used to fill the prompt template
     * @return the model's reply text
     */
    suspend fun execute(
        call: ServerCall,
        authentication: AuthenticationContext,
        promptId: UUID,
        modelId: UUID,
        arguments: JsonObject
    ): String
}
