package bosca.content.metadata.service

import bosca.serialization.UUID

/**
 * Cross-module hook fired when a Metadata document reaches the `published` workflow state. The
 * content module invokes every registered listener (after the publishing transaction commits) with
 * the published [metadataId] and [version]. Modules that own metadata (e.g. ecommerce products)
 * register a `@Provider` implementation to react — advancing a pinned version, re-indexing, etc.
 *
 * Listener failures are isolated and logged; one bad listener does not block the others or the
 * publish itself.
 */
interface MetadataPublishListener {

    /** Called after [metadataId] is published at [version]. */
    suspend fun onPublished(metadataId: UUID, version: Int)
}
