package bosca.recommendations.service

import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Computes the cached [bosca.recommendations.model.PersonalizationSignal] list for profile attributes by
 * running each matching enabled [bosca.recommendations.model.PersonalizationSignalDefinition]'s JSONata
 * against the attribute, and persists it to `ProfileAttribute.signals`. Invoked at attribute write-time
 * (the triggered pipeline) and for backfill when a definition changes.
 */
interface ProfileSignalComputeService : Service {

    /**
     * Recomputes + persists `signals` for the given attributes — e.g. the ids from a
     * `ProfileAttributesAdded` / `ProfileAttributesUpdated` event. Missing attributes are skipped.
     */
    suspend fun computeForAttributes(attributeIds: List<UUID>)

    /**
     * Recomputes + persists `signals` for a single profile's attributes of a given type — e.g. from a
     * `ProfileAttributesVerified` event, which names the profile + type rather than attribute ids (so a
     * `verified`-gated signal updates the moment control of the value is proven).
     */
    suspend fun computeForProfileType(profileId: UUID, typeId: String)

    /**
     * Recomputes + persists `signals` for every attribute of a given source (backfill when a definition
     * changes). Only [PersonalizationSignalSourceType.ATTRIBUTE] sources are cached on attributes.
     */
    suspend fun recomputeForSource(sourceType: PersonalizationSignalSourceType, sourceId: String)
}
