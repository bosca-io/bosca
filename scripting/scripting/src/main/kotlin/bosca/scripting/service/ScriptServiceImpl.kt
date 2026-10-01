package bosca.scripting.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.pubsub.PubSubService
import bosca.scripting.engine.Engine
import bosca.scripting.engine.ScriptSourceValidator
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptInput
import bosca.scripting.model.ScriptType
import bosca.scripting.repository.ScriptPermissionRepository
import bosca.scripting.repository.ScriptRepository
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.builtins.serializer

@ServiceImplementation
class ScriptServiceImpl(
    private val repository: ScriptRepository,
    private val permissionRepository: ScriptPermissionRepository,
    private val engine: Engine,
    private val pubSubService: PubSubService
) : ScriptService {

    private val permissionCache = ServiceCache<UUID, List<EntityPermission>>(
        "scripts:permissions",
        UUIDKeySerializer,
        batchResolver = { keys, batch ->
            val allPermissions = permissionRepository.getPermissionsByScriptIds(keys)
            val grouped = allPermissions.groupBy { it.scriptId }
            keys.forEach { key ->
                batch.setData(key, grouped[key]?.map { it as EntityPermission } ?: emptyList())
            }
        }
    ) {
        permissionRepository.getPermissionsByScriptId(it).map { it as EntityPermission }
    }

    override suspend fun getAll(): List<Script> = repository.getAll()

    override suspend fun getAllIncludingDeleted(): List<Script> = repository.getAllIncludingDeleted()

    override suspend fun getByType(type: ScriptType): List<Script> = repository.getByType(type)

    override suspend fun getByTypeIncludingDeleted(type: ScriptType): List<Script> = repository.getByTypeIncludingDeleted(type)

    override suspend fun get(id: UUID): Script? = repository.getById(id)

    override suspend fun getByIds(ids: Collection<UUID>): List<Script> = repository.getByIds(ids.toList())

    override suspend fun getByKey(key: String): Script? = repository.getByKey(key)

    override suspend fun add(input: ScriptInput): Script {
        engine.validate(input.source)
        val script = Script(
            key = input.key,
            name = input.name,
            description = input.description,
            type = input.type,
            source = input.source,
            public = input.public,
            inputSchema = input.inputSchema,
            outputSchema = input.outputSchema,
            configuration = input.configuration
        )
        return repository.add(script)
    }

    override suspend fun edit(id: UUID, input: ScriptInput): Script {
        engine.validate(input.source)
        val existing = repository.getById(id) ?: throw NoSuchElementException("Script not found: $id")
        val updated = existing.copy(
            key = input.key,
            name = input.name,
            description = input.description,
            type = input.type,
            source = input.source,
            public = input.public,
            inputSchema = input.inputSchema,
            outputSchema = input.outputSchema,
            configuration = input.configuration
        )
        val result = repository.update(updated)
            ?: throw IllegalStateException("Concurrent modification detected for script: $id")
        engine.invalidate(existing.key, existing.version)
        publishInvalidation()
        return result
    }

    override suspend fun delete(id: UUID) {
        val existing = repository.getById(id)
        if (existing != null) {
            engine.invalidate(existing.key, existing.version)
            if (existing.type == ScriptType.EPHEMERAL) {
                repository.softDelete(id)
            } else {
                repository.deleteById(id)
            }
        } else {
            repository.deleteById(id)
        }
        publishInvalidation()
    }

    override suspend fun enable(id: UUID): Script {
        val result = repository.setEnabled(id, true)
        publishInvalidation()
        return result
    }

    override suspend fun disable(id: UUID): Script {
        val existing = repository.getById(id)
        if (existing != null) {
            engine.invalidate(existing.key, existing.version)
        }
        val result = repository.setEnabled(id, false)
        publishInvalidation()
        return result
    }

    override suspend fun getPermissions(entity: Script): List<EntityPermission> {
        return permissionCache.get(entity.id) ?: emptyList()
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        permissionCache.addToBatch(batch)
    }

    override suspend fun addPermission(scriptId: UUID, groupId: UUID, action: PermissionAction) {
        transaction {
            permissionRepository.addPermission(scriptId, groupId, action)
        }
        permissionCache.remove(scriptId)
    }

    override suspend fun deletePermission(scriptId: UUID, groupId: UUID, action: PermissionAction) {
        transaction {
            permissionRepository.deletePermission(scriptId, groupId, action)
        }
        permissionCache.remove(scriptId)
    }

    override suspend fun getKeyIndex(): Map<String, UUID> =
        repository.getKeyIndex().associate { it.key to it.id }

    private suspend fun publishInvalidation() {
        pubSubService.publish(CHANNEL, String.serializer(), "invalidate")
    }

    companion object {
        const val CHANNEL = "scripts:invalidate"
    }
}
