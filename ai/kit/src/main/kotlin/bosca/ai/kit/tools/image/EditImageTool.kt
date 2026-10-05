package bosca.ai.kit.tools.image

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.agents.image.KitImageClient
import bosca.ai.kit.tools.KitTool
import bosca.content.metadata.service.MetadataService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.download
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Edits an existing image with a text prompt via Google Gemini, storing the result as a new metadata item. */
class EditImageTool(
    private val imageClient: KitImageClient,
    private val metadataService: MetadataService,
    private val objectStorageService: ObjectStorageService,
    private val groupEvaluator: GroupEvaluator,
) : KitTool<EditImageTool.Input, EditImageTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "edit_image",
    description = "Edit an existing image using a text prompt. Provide the metadata ID of the source image " +
        "and a description of the edits to apply. The edited image is stored as a new metadata item.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The metadata ID of the existing image to edit.")
        val metadataId: String,
        @property:LLMDescription("A text prompt describing the edits to apply to the image. Be specific about what changes you want.")
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

            val sourceMetadata = metadataService.getById(UUID.parse(input.metadataId))
                ?: return Output("", "", "", false, "Source image metadata not found: ${input.metadataId}")

            val sourceContentType = sourceMetadata.contentType
            if (sourceContentType.isEmpty() || !sourceContentType.startsWith("image/")) {
                return Output("", "", "", false, "Source metadata is not an image: $sourceContentType")
            }

            val sourceBytes = objectStorageService.download(sourceMetadata).use { stream ->
                withContext(Dispatchers.IO) { stream.readBytes() }
            }

            val response = imageClient.require().models.generateContent(
                imageClient.model,
                Content.fromParts(
                    Part.fromBytes(sourceBytes, sourceContentType),
                    Part.fromText(input.prompt),
                ),
                GenerateContentConfig.builder().responseModalities("TEXT", "IMAGE").build(),
            )

            val stored = storeGeneratedImage(response, "Edited Image", metadataService, objectStorageService)
                ?: return Output("", "", "", false, "Model returned no image for this edit prompt")

            Output(
                metadataId = stored.metadataId,
                contentType = stored.contentType,
                downloadUrl = stored.downloadUrl,
                success = true,
                message = "Image edited and stored successfully. Display it using markdown: ![Edited Image](${stored.downloadUrl})",
            )
        } catch (e: Exception) {
            Output("", "", "", false, "Error editing image: ${e.message}")
        }
    }
}
