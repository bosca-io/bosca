package bosca.content.image.service

import bosca.content.collection.model.Collection
import bosca.content.image.model.Coordinates
import bosca.content.image.model.ImageAttachment
import bosca.content.metadata.model.Metadata
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for attaching and optimizing images associated with content items. Handles image processing
 * such as resizing and format conversion for both metadata and collection content.
 */
interface ImageService : Service {

    /**
     * Optimizes the image content associated with a metadata entry, generating
     * optimized variants (e.g., different sizes or formats).
     *
     * @param metadata the metadata entry whose image content should be optimized
     * @return the list of identifiers for the generated optimized image variants
     */
    suspend fun optimize(metadata: Metadata): List<UUID>

    /**
     * Optimizes the image content associated with a collection, generating
     * optimized variants (e.g., different sizes or formats).
     *
     * @param collection the collection whose image content should be optimized
     * @return the list of identifiers for the generated optimized image variants
     */
    suspend fun optimize(collection: Collection): List<UUID>

    /**
     * Attaches an uploaded image through the metadata's document or data template.
     * An omitted key selects the sole image attribute or returns choices without writing.
     * The caller must authorize source VIEW and target EDIT before invoking this method.
     */
    suspend fun attach(image: Metadata, target: Metadata, attributeKey: String?, crop: Coordinates?): ImageAttachment

    /**
     * Attaches an uploaded image through the collection template, optionally in a language variant.
     * The caller must authorize source VIEW and EDIT on the selected collection or variant.
     * List attributes preserve other images; a single-image attribute replaces its relationship.
     */
    suspend fun attach(image: Metadata, target: Collection, languageTag: String?, attributeKey: String?, crop: Coordinates?): ImageAttachment
}
