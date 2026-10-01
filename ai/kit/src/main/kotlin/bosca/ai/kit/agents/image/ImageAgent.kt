package bosca.ai.kit.agents.image

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.structuredOutputWithToolsStrategy
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.agents.KitSubAgent
import bosca.ai.kit.agents.session.BoscaChatMemoryProvider
import bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.image.AttachImageTool
import bosca.ai.kit.tools.image.EditImageTool
import bosca.ai.kit.tools.image.GenerateImageTool

/**
 * Kit's image specialist. It owns the Gemini-backed generate/edit image tools and runs Koog's
 * **structured-output-with-tools** strategy: a tool-calling loop that creates or edits an image and
 * stores it as content, ending by producing a structured [ImageResponse] (with the markdown reference
 * to the stored image). Identity flows through the ambient `KitToolContext` the tools resolve, so the
 * editor-group permission check is performed as the calling user.
 */
class ImageAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    images: ImageServices,
    json: KitJson,
    sessionService: KitSessionService?,
) : KitSubAgent<ImageRequest, ImageResponse>() {

    private val systemPrompt = """
        You are Kit's image specialist. You generate new images from text prompts and edit existing
        images, using ONLY the provided tools — never claim to have made an image you didn't.

        ## How to work
        - GENERATE a new image with generate_image, passing a specific, descriptive prompt.
        - EDIT an existing image with edit_image, passing the source image's metadata id and a clear
          description of the changes. The user must give you (or you must already know) the metadata id
          of the image to edit — ask via the conversation if it's missing.
        - ATTACH a stored image to metadata or a collection only when the user asks. Use attach_image
          with the target id. Its available attributes come from that target's template. If it returns
          multiple choices, choose from their names, descriptions, and image.* relationships when the
          intent is clear; otherwise ask which attribute the user wants. Never invent an attribute key.
        - Image generation and editing store their result as a new content item and return a markdown
          image reference. Include that reference in your summary so the image renders for the user.
        - Image generation requires editor permissions and a configured image model; if a tool reports
          it isn't configured or permitted, relay that plainly rather than retrying.

        Return a concise summary of what you produced or attached. Embed the markdown image link when
        the generation or editing tool supplies one; otherwise relay the result or error plainly.
    """.trimIndent()

    private val responseConfig = kitStructuredConfig(ImageResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<ImageRequest, ImageResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("image") {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 50,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = structuredOutputWithToolsStrategy(responseConfig) { request ->
            "Carry out this image request using the tools:\n\n" + request.message.parts.joinToString("\n") { it.text }
        },
        toolRegistry = ToolRegistry {
            tool(GenerateImageTool(images.imageClient, images.metadataService, images.objectStorageService, images.groupEvaluator))
            tool(EditImageTool(images.imageClient, images.metadataService, images.objectStorageService, images.groupEvaluator))
            tool(AttachImageTool(images))
        },
        installFeatures = {
            sessionService?.let {
                install(Persistence) { storage = BoscaPersistenceStorageProvider(it) }
                install(ChatMemory) { chatHistoryProvider = BoscaChatMemoryProvider(it) }
            }
        },
    )
}
