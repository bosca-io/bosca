package bosca.content.image.service

import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for optimizing images associated with content items. Handles image processing
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
}