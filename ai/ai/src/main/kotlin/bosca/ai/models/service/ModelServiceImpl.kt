package bosca.ai.models.service

import bosca.ai.models.model.Model
import bosca.ai.models.model.ModelInput
import bosca.ai.models.repository.ModelRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class ModelServiceImpl(private val repository: ModelRepository) : ModelService {

    override suspend fun getAll(): List<Model> = repository.getAll().toList()

    override suspend fun get(id: UUID): Model = repository.getById(id)

    override suspend fun getByKey(key: String) = repository.getByKey(key)

    override suspend fun add(input: ModelInput): Model {
        val model = Model(
            key = input.key,
            type = input.type,
            name = input.name,
            description = input.description,
            configuration = input.configuration
        )
        return repository.add(model)
    }

    override suspend fun edit(id: UUID, input: ModelInput): Model {
        val existing = repository.getById(id)
        val updated = existing.copy(
            key = input.key,
            type = input.type,
            name = input.name,
            description = input.description,
            configuration = input.configuration
        )
        return repository.update(updated)
    }

    override suspend fun delete(id: UUID) {
        repository.deleteById(id)
    }

    override suspend fun getKeyIndex(): Map<String, UUID> =
        repository.getKeyIndex().associate { it.key to it.id }
}
