package bosca.content.image.service

import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.service.Service

/** Creates stored images using the configured AI image provider. */
interface ImageGenerationService : Service {
    /**
     * Generates an image from [prompt], optionally editing [source], and stores a new uploaded
     * metadata item named [name] in [parent]. The source remains unchanged. Callers authorize
     * source content reads and creation in the destination before invoking this method.
     * Provider, storage and cancellation failures propagate; an uncertain call must not be retried automatically.
     */
    suspend fun generate(prompt: String, name: String, source: Metadata?, parent: Collection?): Metadata
}
