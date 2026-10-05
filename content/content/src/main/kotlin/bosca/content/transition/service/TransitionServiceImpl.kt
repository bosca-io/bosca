package bosca.content.transition.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.content.transition.model.Transition
import bosca.content.transition.model.TransitionInput
import bosca.content.transition.repository.TransitionRepository
import bosca.db.transaction
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class TransitionServiceImpl(
    private val repository: TransitionRepository
) : TransitionService {

    private val cache = ServiceCache(
        "transition:cache",
        StringKeySerializer,
        resolver = {
            val parts = it.split("::")
            repository.findByFromStateIdAndToStateId(parts.first(), parts.last())
        }
    )

    override suspend fun getAll(): List<Transition> = repository.getAll()

    override suspend fun get(fromStateId: String, toStateId: String): Transition? {
        val key = "$fromStateId::$toStateId"
        return cache.get(key)
    }

    override suspend fun add(input: TransitionInput): Transition {
        val transition = Transition(
            fromStateId = input.fromStateId,
            toStateId = input.toStateId,
            description = input.description,
            enterJobName = input.enterJobName,
            exitJobName = input.exitJobName,
            configuration = input.configuration
        )
        val newTransition = repository.add(transition)
        cache.clear()
        return newTransition
    }

    override suspend fun edit(input: TransitionInput): Transition = transaction {
        val existing = repository.findByFromStateIdAndToStateId(input.fromStateId, input.toStateId) ?: throw NoSuchElementException("Transition not found: ${input.fromStateId} -> ${input.toStateId}")
        val updated = existing.copy(
            description = input.description,
            enterJobName = input.enterJobName,
            exitJobName = input.exitJobName,
            configuration = input.configuration
        )
        val t = repository.update(updated)
        cache.clear()
        t
    }

    override suspend fun delete(fromStateId: String, toStateId: String) {
        repository.deleteByFromStateIdAndToStateId(fromStateId, toStateId)
        cache.clear()
    }
}
