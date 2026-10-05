package bosca.recommendations.service

import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalDefinitionInput
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages **Personalization Signal** definitions — the admin config telling the recommender which
 * profile attributes / segments personalize results, how each keyed value is derived (JSONata) and
 * typed, and how it is used (feature and/or cohort). Definitions are read by the write-time compute
 * pipeline (which caches `PersonalizationSignal { key, value }` onto attributes) and by serving.
 */
interface PersonalizationSignalService : Service {

    /** All definitions, highest priority first, paged. */
    suspend fun getAll(offset: Long, limit: Int): List<PersonalizationSignalDefinition>

    /** The enabled definitions, highest priority first — the active config the engine evaluates. */
    suspend fun getEnabled(): List<PersonalizationSignalDefinition>

    /** A single definition by id, or null if absent. */
    suspend fun getById(id: UUID): PersonalizationSignalDefinition?

    /** The enabled definitions sourced from a given attribute type / segment (the compute pipeline's lookup). */
    suspend fun getEnabledBySource(
        sourceType: PersonalizationSignalSourceType,
        sourceId: String,
    ): List<PersonalizationSignalDefinition>

    /**
     * Creates a definition. Validates: [PersonalizationSignalDefinitionInput.key] is non-blank + unique,
     * the [PersonalizationSignalDefinitionInput.expression] parses as JSONata, and a `useAsCohort`
     * definition is CATEGORICAL/BOOLEAN.
     */
    suspend fun add(input: PersonalizationSignalDefinitionInput): PersonalizationSignalDefinition

    /** Updates a definition (same validation as [add]); throws if [id] is unknown. */
    suspend fun edit(id: UUID, input: PersonalizationSignalDefinitionInput): PersonalizationSignalDefinition

    /** Deletes a definition. */
    suspend fun delete(id: UUID)
}
