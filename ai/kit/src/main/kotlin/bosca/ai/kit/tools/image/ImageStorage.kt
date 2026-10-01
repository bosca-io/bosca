package bosca.ai.kit.tools.image

import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.MetadataService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import com.google.genai.types.FinishReason
import com.google.genai.types.GenerateContentResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.Uuid

/** Where a stored generated/edited image lives: its new [metadataId], [contentType], and [downloadUrl]. */
internal data class StoredImage(
    val metadataId: String,
    val contentType: String,
    val downloadUrl: String,
)

/**
 * Extracts the first inline image from a Gemini [response], writes it to a temp file, creates a
 * metadata item, uploads the bytes to object storage, and marks it uploaded — returning the
 * [StoredImage] pointer, or `null` if the model returned no image. Shared by the generate/edit tools.
 */
internal suspend fun storeGeneratedImage(
    response: GenerateContentResponse,
    name: String,
    metadataService: MetadataService,
    objectStorageService: ObjectStorageService,
): StoredImage? {
    if (response.finishReason().knownEnum() == FinishReason.Known.NO_IMAGE) return null

    val files = mutableListOf<File>()
    try {
        var mimeType = "image/png"
        var outputFile: File? = null
        for (part in response.parts() ?: emptyList()) {
            val blob = part.inlineData().orElse(null) ?: continue
            if (blob.data().isPresent) {
                blob.mimeType().ifPresent { mimeType = it }
                val suffix = if (mimeType.startsWith("image/")) ".${mimeType.substringAfterLast('/')}" else ".bin"
                val file = withContext(Dispatchers.IO) { File.createTempFile("kit-img-${Uuid.random()}", suffix) }
                files.add(file)
                withContext(Dispatchers.IO) { file.writeBytes(blob.data().get()) }
                outputFile = file
                break
            }
        }

        val file = outputFile ?: return null
        val metadata = metadataService.add(
            null,
            null,
            MetadataInput(
                name = name,
                contentLength = file.length(),
                contentType = mimeType,
                languageTag = "en",
            ),
        )
        objectStorageService.upload(metadata, null, file)
        metadataService.setUploaded(metadata.id, mimeType, file.length())

        return StoredImage(
            metadataId = metadata.id.toString(),
            contentType = mimeType,
            downloadUrl = "/api/v1/content/metadata/download?id=${metadata.id}",
        )
    } finally {
        files.forEach { it.delete() }
    }
}
