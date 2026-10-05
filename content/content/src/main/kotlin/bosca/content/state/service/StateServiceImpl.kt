package bosca.content.state.service

import bosca.content.state.model.State
import bosca.content.state.model.StateInput
import bosca.content.state.repository.StateRepository
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class StateServiceImpl(
    private val repository: StateRepository
) : StateService {
    override suspend fun getAll() = repository.getAll()

    override suspend fun get(id: String) = repository.getById(id)

    override suspend fun add(input: StateInput): State {
        val state = State(
            id = input.id,
            name = input.name,
            description = input.description,
            type = input.type,
            configuration = input.configuration,
            jobName = input.jobName
        )
        return repository.add(state)
    }

    override suspend fun edit(id: String, input: StateInput): State {
        val existing = repository.getById(id) ?: throw NoSuchElementException("State not found: $id")
        val updated = existing.copy(
            name = input.name,
            description = input.description,
            type = input.type,
            configuration = input.configuration,
            jobName = input.jobName
        )
        return repository.update(updated)
    }

    override suspend fun delete(id: String) = repository.deleteById(id)
}
