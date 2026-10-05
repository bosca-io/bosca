package bosca.recommendations.service

import bosca.recommendations.jobs.RecomputeProfileSignalsJob
import bosca.recommendations.jobs.enqueue
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalDefinitionInput
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.recommendations.model.PersonalizationSignalValueType
import bosca.recommendations.repository.PersonalizationSignalRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import com.dashjoin.jsonata.Jsonata

/**
 * Default [PersonalizationSignalService]: CRUD over [PersonalizationSignalRepository] with validation of
 * each definition — a non-blank, unique [PersonalizationSignalDefinition.key]; a parseable JSONata
 * expression; and the cohort rule (a `useAsCohort` definition must produce a finite label, i.e.
 * CATEGORICAL or BOOLEAN, so each cohort membership has a finite label).
 *
 * Every definition change backfills the cached signals on already-persisted attributes: a
 * [RecomputeProfileSignalsJob] is enqueued for the affected source(s) (an edit that moves the source
 * backfills both the old and new one). A source can span many profiles, so the recompute runs on the
 * job queue rather than in the mutation.
 */
@ServiceImplementation
class PersonalizationSignalServiceImpl(
    private val repository: PersonalizationSignalRepository,
) : PersonalizationSignalService {

    override suspend fun getAll(offset: Long, limit: Int): List<PersonalizationSignalDefinition> =
        repository.getAll(offset, limit)

    override suspend fun getEnabled(): List<PersonalizationSignalDefinition> = repository.getEnabled()

    override suspend fun getById(id: UUID): PersonalizationSignalDefinition? = repository.getById(id)

    override suspend fun getEnabledBySource(
        sourceType: PersonalizationSignalSourceType,
        sourceId: String,
    ): List<PersonalizationSignalDefinition> = repository.getEnabledBySource(sourceType, sourceId)

    override suspend fun add(input: PersonalizationSignalDefinitionInput): PersonalizationSignalDefinition {
        validate(input, existingId = null)
        return repository.add(input.toDefinition()).also { backfill(it.sourceType, it.sourceId) }
    }

    override suspend fun edit(id: UUID, input: PersonalizationSignalDefinitionInput): PersonalizationSignalDefinition {
        val existing = repository.getById(id) ?: throw NoSuchElementException("Personalization signal not found: $id")
        validate(input, existingId = id)
        return repository.update(input.toDefinition(id)).also { updated ->
            backfill(updated.sourceType, updated.sourceId)
            // A moved source leaves stale signals on the old source's attributes — backfill it too.
            if (existing.sourceType != updated.sourceType || existing.sourceId != updated.sourceId) {
                backfill(existing.sourceType, existing.sourceId)
            }
        }
    }

    override suspend fun delete(id: UUID) {
        val existing = repository.getById(id)
        repository.deleteById(id)
        existing?.let { backfill(it.sourceType, it.sourceId) }
    }

    /** Enqueues a recompute of every persisted attribute drawn from [sourceType]/[sourceId]. */
    private suspend fun backfill(sourceType: PersonalizationSignalSourceType, sourceId: String) {
        RecomputeProfileSignalsJob(sourceType, sourceId).enqueue()
    }

    private suspend fun validate(input: PersonalizationSignalDefinitionInput, existingId: UUID?) {
        require(input.key.isNotBlank()) { "Personalization signal key must not be blank" }
        require(input.sourceId.isNotBlank()) { "Personalization signal sourceId must not be blank" }
        // A cohort membership must be a finite label; a profile may have several values for the same key.
        if (input.useAsCohort) {
            require(
                input.valueType == PersonalizationSignalValueType.CATEGORICAL ||
                    input.valueType == PersonalizationSignalValueType.BOOLEAN,
            ) { "A useAsCohort signal must be CATEGORICAL or BOOLEAN (got ${input.valueType})" }
        }
        // The expression must parse as a JSONata program (dashjoin JSONata; parse errors throw). Synchronous,
        // so no CancellationException concern.
        try {
            Jsonata.jsonata(input.expression)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSONata expression: ${e.message}")
        }
        // Keys are unique across definitions (allowing a definition to keep its own key on edit).
        val clash = repository.getByKey(input.key)
        require(clash == null || clash.id == existingId) {
            "A personalization signal with key '${input.key}' already exists"
        }
    }

    private fun PersonalizationSignalDefinitionInput.toDefinition(id: UUID = UUID.NIL) =
        PersonalizationSignalDefinition(
            id = id,
            key = key,
            sourceType = sourceType,
            sourceId = sourceId,
            expression = expression,
            valueType = valueType,
            priority = priority,
            useAsFeature = useAsFeature,
            useAsCohort = useAsCohort,
            enabled = enabled,
        )
}
