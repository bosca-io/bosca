package bosca.ai.kit.image

import bosca.ai.kit.agents.image.KitImageClient
import bosca.content.collection.model.Collection
import bosca.content.image.service.ImageGenerationService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.MetadataService
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StorageDispatcher
import bosca.storage.service.download
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import com.google.genai.types.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Gemini-backed image generation for the content image APIs. */
@ServiceImplementation
class KitImageGenerationService(
    private val imageClient: KitImageClient,
    private val metadataService: MetadataService,
    private val storage: ObjectStorageService,
) : ImageGenerationService {
    override suspend fun generate(prompt: String, name: String, source: Metadata?, parent: Collection?): Metadata {
        require(prompt.isNotBlank()) { "Image prompt must not be blank" }
        require(name.isNotBlank()) { "Image name must not be blank" }
        if (source != null) {
            require(source.contentType.startsWith("image/") && source.uploaded != null && source.contentLength != null) {
                "Source must be an uploaded image"
            }
        }
        val client = imageClient.require()
        val input = if (source == null) Content.fromParts(Part.fromText(prompt)) else {
            val bytes = storage.download(source).use { stream -> runInterruptible(Dispatchers.IO) { stream.readBytes() } }
            Content.fromParts(Part.fromBytes(bytes, source.contentType), Part.fromText(prompt))
        }
        val response = runInterruptible(Dispatchers.IO) {
            client.models.generateContent(imageClient.model, input,
                GenerateContentConfig.builder().responseModalities("TEXT", "IMAGE")
                    .httpOptions(HttpOptions.builder().retryOptions(HttpRetryOptions.builder().attempts(1)))
                    .build())
        }
        val image = response.parts().orEmpty().firstNotNullOfOrNull { part ->
            part.inlineData().orElse(null)?.takeIf { blob ->
                blob.mimeType().orElse("").startsWith("image/") && blob.data().orElse(null)?.isNotEmpty() == true
            }
        } ?: error("Model returned no image")
        val contentType = image.mimeType().orElseThrow()
        val bytes = image.data().orElseThrow()
        val metadata = metadataService.add(parent, null, MetadataInput(
            parentCollectionId = parent?.id, name = name, contentLength = bytes.size.toLong(),
            contentType = contentType, languageTag = source?.languageTag ?: "en",
        ))
        withContext(StorageDispatcher) {
            val path = storage.getPath(metadata)
            bytes.inputStream().use { storage.setInputStream(path, it, bytes.size.toLong()) }
        }
        metadataService.setUploaded(metadata.id, contentType, bytes.size.toLong())
        return metadataService.getById(metadata.id) ?: error("Stored image metadata is unavailable")
    }
}
