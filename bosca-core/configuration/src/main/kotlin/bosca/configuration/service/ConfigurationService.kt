package bosca.configuration.service

import bosca.cache.CacheKey
import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UUIDKeySerializer
import bosca.configuration.model.Configuration
import bosca.configuration.model.ConfigurationInput
import bosca.configuration.model.ConfigurationPermission
import bosca.configuration.model.ConfigurationValue
import bosca.configuration.repository.ConfigurationPermissionRepository
import bosca.configuration.repository.ConfigurationRepository
import bosca.configuration.repository.ConfigurationValueRepository
import bosca.db.transaction
import bosca.graphql.Batch
import bosca.security.encryption.EncryptionService
import bosca.security.model.EntityPermission
import bosca.serialization.JsonConverter.parseToJsonElement
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement

@ServiceImplementation
class ConfigurationServiceImpl(
    private val repository: ConfigurationRepository,
    private val valueRepository: ConfigurationValueRepository,
    private val permissions: ConfigurationPermissionRepository,
    private val encryption: EncryptionService,
) : ConfigurationService {

    private val permissionsCache = ServiceCache<UUID, List<EntityPermission>>(cacheName = "configuration:permissions", UUIDKeySerializer) {
        permissions.findConfigurationPermissionsByConfigurationId(it)
    }

    private val configurationCache = ServiceCache(cacheName = "configuration:key", StringKeySerializer) {
        repository.getConfigurationByKey(it)
    }

    private val valueCache = ServiceCache(cacheName = "configuration:value", UUIDKeySerializer) {
        valueRepository.getById(it)
    }

    private suspend fun clearCache(key: String, id: UUID) {
        configurationCache.remove(key)
        valueCache.remove(id)
        permissionsCache.remove(id)
    }

    override suspend fun getAll(): List<Configuration> {
        return repository.getAll()
    }

    override suspend fun getByKey(key: String) = configurationCache.get(key)

    override suspend fun getValue(id: UUID): JsonElement? {
        val value = valueCache.get(id) ?: return null
        val encrypted = EncryptionService.Encrypted(value.nonce, value.value ?: return null)
        val data = encryption.decrypt(encrypted, id)
        return data.decodeToString().parseToJsonElement()
    }

    override suspend fun setValue(id: UUID, value: JsonElement) = transaction {
        internalSetValue(id, value)
    }

    private suspend fun internalSetValue(id: UUID, value: JsonElement) {
        val jsonAsBytes = value.toString().toByteArray(Charsets.UTF_8)
        val data = encryption.encrypt(jsonAsBytes, id)
        val configValue = ConfigurationValue(
            configurationId = id,
            value = data.data,
            nonce = data.nonce
        )
        valueRepository.deleteById(id)
        valueRepository.add(configValue)
        valueCache.remove(id)
    }

    override suspend fun getPermissions(entity: Configuration): List<EntityPermission> = permissionsCache.get(entity.id) ?: emptyList()

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        permissionsCache.addToBatch(batch)
    }

    override suspend fun setConfiguration(input: ConfigurationInput): Configuration = transaction {
        val existing = getByKey(input.key)
        val toSave = existing?.copy(
            description = input.description,
            public = input.public
        ) ?: Configuration(
            id = UUID.random(),
            key = input.key,
            description = input.description,
            public = input.public
        )

        if (existing != null) {
            repository.deleteConfigurationById(toSave.id)
        }

        val saved = repository.add(toSave)
        val configurationId = saved.id

        internalSetValue(configurationId, input.value)

        permissions.deleteByConfigurationId(configurationId)
        for (permission in input.permissions) {
            permissions.add(
                ConfigurationPermission(
                    configurationId = configurationId,
                    action = permission.action,
                    groupId = permission.groupId
                )
            )
        }

        clearCache(input.key, configurationId)
        saved
    }

    override suspend fun deleteConfiguration(key: String): UUID {
        val configuration = repository.getConfigurationByKey(key) ?: return UUID.NIL
        repository.deleteConfigurationByKey(key)
        val id = configuration.id
        clearCache(key, id)
        return id
    }
}