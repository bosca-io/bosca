@file:OptIn(ExperimentalUuidApi::class)

package bosca.storage.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UUIDKeySerializer
import bosca.cache.serializers.UnitKeySerializer
import bosca.db.transaction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.events.StorageSystemAdded
import bosca.storage.events.StorageSystemEdited
import bosca.storage.events.dispatch
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemInput
import bosca.storage.model.StorageSystemModel
import bosca.storage.repository.StorageSystemModelRepository
import bosca.storage.repository.StorageSystemRepository
import kotlin.uuid.ExperimentalUuidApi

@ServiceImplementation
class StorageSystemServiceImpl(
    private val repository: StorageSystemRepository,
    private val modelRepository: StorageSystemModelRepository,
) : StorageSystemService {

    private val systemsAll = ServiceCache(cacheName = "storagesystems:all", UnitKeySerializer) {
        repository.getAll()
    }

    private val systemsById = ServiceCache(cacheName = "storagesystems:id", UUIDKeySerializer) {
        repository.getById(it)
    }

    private val systemsByName = ServiceCache(cacheName = "storagesystems:name", StringKeySerializer) {
        repository.findByName(it)
    }

    private val systemModels = ServiceCache(cacheName = "storagesystems:models", UUIDKeySerializer) {
        modelRepository.findBySystemId(it)
    }

    override suspend fun getAll(): List<StorageSystem> = systemsAll.get(Unit) ?: emptyList()

    override suspend fun get(id: UUID) = systemsById.get(id)

    override suspend fun getByName(name: String) = systemsByName.get(name)

    override suspend fun getModels(systemId: UUID) = systemModels.get(systemId) ?: emptyList()

    private suspend fun clearCache(id: UUID, name: String?) {
        systemsAll.clear()
        systemsById.remove(id)
        systemModels.remove(id)
        name?.let { systemsByName.remove(it) }
    }

    override suspend fun add(input: StorageSystemInput): StorageSystem {
        val system = StorageSystem(
            name = input.name,
            description = input.description,
            type = input.type,
            configuration = input.configuration
        )
        val saved = repository.add(system)
        for (m in input.models) {
            modelRepository.add(
                StorageSystemModel(
                    systemId = saved.id,
                    modelId = m.modelId,
                    configuration = m.configuration
                )
            )
        }
        systemsAll.clear()
        systemsByName.clear()
        StorageSystemAdded(saved.id).dispatch()
        return saved
    }

    override suspend fun edit(id: UUID, input: StorageSystemInput): StorageSystem = transaction {
        val existing = repository.getById(id) ?: error("Storage system $id not found")
        val updated = existing.copy(
            name = input.name,
            description = input.description,
            type = input.type,
            configuration = input.configuration
        )
        val saved = repository.update(updated)
        modelRepository.deleteBySystemId(saved.id)
        for (m in input.models) {
            modelRepository.add(
                StorageSystemModel(
                    systemId = saved.id,
                    modelId = m.modelId,
                    configuration = m.configuration
                )
            )
        }
        clearCache(saved.id, existing.name)
        StorageSystemEdited(saved.id).dispatch()
        saved
    }

    override suspend fun delete(id: UUID) {
        val existing = repository.getById(id) ?: error("Storage system $id not found")
        modelRepository.deleteBySystemId(id)
        repository.deleteById(id)
        clearCache(id, existing.name)
    }
}
