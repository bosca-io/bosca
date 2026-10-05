package bosca.trait.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UnitKeySerializer
import bosca.service.annotation.ServiceImplementation
import bosca.trait.model.Trait
import bosca.trait.model.TraitInput
import bosca.trait.repository.TraitRepository

@ServiceImplementation
class TraitServiceImpl(private val repository: TraitRepository) : TraitService {

    private val traitAll = ServiceCache("traits:all", UnitKeySerializer) {
        repository.getAll().toList()
    }

    private val traitId = ServiceCache("traits", StringKeySerializer, { keys, batch ->
        val all = repository.getAll(keys).associateBy { it.id }
        keys.forEach { key ->
            all[key]?.let { batch.setData(key, it) }
        }
    }) {
        repository.getById(it)
    }

    override suspend fun getAll() = traitAll.get(Unit) ?: emptyList()

    override suspend fun getAll(ids: List<String>): List<Trait> {
        return traitId.getAll(ids).filterNotNull()
    }

    override suspend fun get(id: String) = traitId.get(id)

    override suspend fun add(input: TraitInput): Trait {
        val category = Trait(
            name = input.name,
            description = input.description,
            id = input.id,
            deleteWorkflowId = input.deleteWorkflowId
        )
        val added = repository.add(category)
        traitAll.clear()
        traitId.put(input.id, added)
        return added
    }

    override suspend fun edit(input: TraitInput): Trait {
        val existing = repository.getById(input.id) ?: throw NoSuchElementException("Trait not found: ${input.id}")
        var updated = existing.copy(name = input.name)
        updated = repository.update(updated)
        traitAll.clear()
        traitId.remove(input.id)
        return updated
    }

    override suspend fun delete(id: String) {
        repository.deleteById(id)
        traitAll.clear()
        traitId.remove(id)
    }

    override suspend fun getWorkflowIds(traitId: String): List<String> {
        TODO("Not yet implemented")
    }

    override suspend fun getContentTypes(traitId: String): List<String> {
        TODO("Not yet implemented")
    }

    override suspend fun getTraitsByContentType(contentType: String): List<Trait> {
        return repository.getByContentType(contentType)
    }
}
