package bosca.ecommerce.service

import bosca.content.metadata.service.MetadataPublishListener
import bosca.serialization.UUID

/**
 * Bridges the content-module publish signal to ecommerce: when a product's backing document is
 * published, advance the product's pinned `metadata_version` (a no-op if the metadata isn't a
 * product or the version isn't newer). Registered as a named `@Provider` so content's
 * `ProviderRegistry.findAll(MetadataPublishListener::class)` picks it up.
 */
class EcommerceMetadataPublishListener(
    private val productService: ProductService,
) : MetadataPublishListener {

    override suspend fun onPublished(metadataId: UUID, version: Int) {
        productService.onContentPublished(metadataId, version)
    }
}
