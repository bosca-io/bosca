package bosca.ai.kit.tools.image

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.agents.image.KitImageClient
import bosca.ai.kit.tools.KitTool
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.storage.service.ObjectStorageService
import com.google.genai.types.GenerateContentConfig
import kotlinx.serialization.Serializable

/** Generates an image from a text prompt via Google Gemini, storing it as a new metadata item. */
class GenerateImageTool(
    private val imageClient: KitImageClient,
    private val metadataService: MetadataService,
    private val objectStorageService: ObjectStorageService,
    private val groupEvaluator: GroupEvaluator,
) : KitTool<GenerateImageTool.Input, GenerateImageTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "generate_image",
    description = "Generate an image from a text prompt using Google's Gemini image generation. " +
        "The generated image is stored as a new metadata item.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The text prompt describing the image to generate. Be specific and descriptive.")
        val prompt: String,
    )

    @Serializable
    data class Output(
        val metadataId: String,
        val contentType: String,
        val downloadUrl: String,
        val success: Boolean,
        val message: String,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        return try {
            groupEvaluator.verifyHasEditorGroup(authentication)

            val response = imageClient.require().models.generateContent(
                imageClient.model,
                input.prompt,
                GenerateContentConfig.builder().responseModalities("TEXT", "IMAGE").build(),
            )

            val stored = storeGeneratedImage(response, "Generated Image", metadataService, objectStorageService)
                ?: return Output("", "", "", false, "Model returned no image for this prompt")

            Output(
                metadataId = stored.metadataId,
                contentType = stored.contentType,
                downloadUrl = stored.downloadUrl,
                success = true,
                message = "Image generated and stored successfully. Display it using markdown: ![Generated Image](${stored.downloadUrl})",
            )
        } catch (e: Exception) {
            Output("", "", "", false, "Error generating image: ${e.message}")
        }
    }
}
