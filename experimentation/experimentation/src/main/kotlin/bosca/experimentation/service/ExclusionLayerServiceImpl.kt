package bosca.experimentation.service

import bosca.db.transaction
import bosca.experimentation.model.ExclusionLayer
import bosca.experimentation.model.ExclusionLayerInput
import bosca.experimentation.model.Experiment
import bosca.experimentation.repository.ExclusionLayerRepository
import bosca.experimentation.repository.ExperimentRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Implementation of [ExclusionLayerService] that manages mutual exclusion layers
 * for preventing conflicting experiment enrollments.
 */
@ServiceImplementation
class ExclusionLayerServiceImpl(
    private val exclusionLayerRepository: ExclusionLayerRepository,
    private val experimentRepository: ExperimentRepository
) : ExclusionLayerService {

    override suspend fun getAll(): List<ExclusionLayer> {
        return exclusionLayerRepository.getAll()
    }

    override suspend fun getById(id: UUID): ExclusionLayer? {
        return exclusionLayerRepository.getById(id)
    }

    override suspend fun add(input: ExclusionLayerInput): ExclusionLayer {
        val layer = ExclusionLayer(
            name = input.name,
            description = input.description ?: ""
        )
        return exclusionLayerRepository.add(layer)
    }

    override suspend fun edit(id: UUID, input: ExclusionLayerInput): ExclusionLayer {
        val existing = exclusionLayerRepository.getById(id) ?: error("Exclusion layer not found: $id")
        val updated = existing.copy(
            name = input.name,
            description = input.description ?: existing.description
        )
        return exclusionLayerRepository.update(updated)
    }

    override suspend fun delete(id: UUID) {
        exclusionLayerRepository.deleteById(id)
    }

    override suspend fun getExperiments(layerId: UUID): List<Experiment> {
        return experimentRepository.getByLayerId(layerId)
    }

    override suspend fun getExperiments(layerId: UUID, offset: Long, limit: Int): List<Experiment> {
        return experimentRepository.getByLayerId(layerId, offset, limit)
    }
}
