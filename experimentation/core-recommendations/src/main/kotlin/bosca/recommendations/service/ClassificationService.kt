package bosca.recommendations.service

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Classifies a content item and records the classification **on the item itself** as normalized content
 * categories. This is the recommendations-domain step that makes content recommendable —
 * but the step *is* classification; "recommendability" is the goal the triggered pipeline serves.
 *
 * There is no recommendations-owned projection of the item: the recommend half reads categories straight
 * from content. The curated topic collections are used only as a read-only classification signal; they
 * are never written to (topics are curated, not an ML-output sink).
 */
interface ClassificationService : Service {

    /**
     * Classify [metadataId] and assign the resulting normalized categories to it; returns the assigned
     * category ids. Idempotent — re-running re-derives and re-sets the item's categories.
     */
    suspend fun classify(metadataId: UUID): List<UUID>
}
