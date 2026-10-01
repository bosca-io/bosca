package bosca.category.service

import bosca.cache.CacheKey
import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.serializers.UnitKeySerializer
import bosca.category.model.Category
import bosca.category.model.CategoryInput
import bosca.category.repository.CategoryRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class CategoryServiceImpl(private val repository: CategoryRepository) : CategoryService {

    private val categoryAll = ServiceCache("categories:all", UnitKeySerializer) {
        repository.getAll()
    }

    private val categoryIds = ServiceCache("categories", UUIDKeySerializer, { keys, batch ->
        val all = repository.getAll(keys).associateBy { it.id }
        keys.forEach { key ->
            all[key]?.let { batch.setData(key, it) }
        }
    }) {
        repository.getById(it)
    }

    override suspend fun getAll() = categoryAll.get(Unit) ?: emptyList()

    override suspend fun getAll(ids: List<UUID>): List<Category> {
        return categoryIds.getAll(ids).filterNotNull()
    }

    override suspend fun add(input: CategoryInput): Category {
        val category = Category(
            name = input.name
        )
        val added = repository.addCategory(category)
        categoryAll.clear()
        return added
    }

    override suspend fun edit(id: UUID, input: CategoryInput): Category {
        val existing = repository.getById(id) ?: throw NoSuchElementException("Category not found: $id")
        var updated = existing.copy(name = input.name)
        updated = repository.editCategory(updated)
        categoryAll.clear()
        categoryIds.remove(id)
        return updated
    }

    override suspend fun delete(id: UUID) {
        repository.deleteById(id)
        categoryAll.clear()
        categoryIds.remove(id)
    }
}
