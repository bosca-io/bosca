package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.*
import kotlin.uuid.Uuid

/** Generates, attaches, and retrieves stored images through the caller's content access. */
class ContentImages(private val network: NetworkClient) {
    suspend fun generate(prompt: String, name: String, parentCollectionId: Uuid?): IStoredImage =
        network.boscaGraphql.execute(GenerateContentImage, GenerateContentImage.Variables(prompt, name, parentCollectionId)).content.images.generate

    suspend fun edit(sourceId: Uuid, prompt: String, name: String, parentCollectionId: Uuid?): IStoredImage =
        network.boscaGraphql.execute(EditContentImage, EditContentImage.Variables(sourceId, prompt, name, parentCollectionId)).content.images.edit

    suspend fun get(id: Uuid): IStoredImage? =
        network.boscaGraphql.execute(GetContentImage, GetContentImage.Variables(id)).content.metadata

    /** Attaches through the target template, or returns choices when the image attribute is ambiguous. */
    suspend fun attach(imageMetadataId: Uuid, targetMetadataId: Uuid?, targetCollectionId: Uuid?, targetLanguageTag: String?, attributeKey: String?, crop: ImageCropInput?) =
        network.boscaGraphql.execute(AttachContentImage, AttachContentImage.Variables(imageMetadataId, targetMetadataId, targetCollectionId, targetLanguageTag, attributeKey, crop))

    suspend fun preview(id: Uuid): ByteArray {
        val download = ContentMetadata(network).getMetadataContentDownload(id) ?: error("Image download is unavailable")
        return Files(network).downloadBytes(download.url, download.headers)
    }
}
