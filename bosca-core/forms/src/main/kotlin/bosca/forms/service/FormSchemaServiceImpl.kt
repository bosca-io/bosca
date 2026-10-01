package bosca.forms.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.StringKeySerializer
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaInput
import bosca.forms.model.FormSchemaProfileMapping
import bosca.forms.model.FormSchemaType
import bosca.forms.model.FormSchemaPermission
import bosca.forms.repository.FormSchemaPermissionRepository
import bosca.forms.repository.FormSchemaRepository
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json

/**
 * Implementation of the form schema service backed by the form_schemas
 * table with a key-based cache for efficient repeated lookups.
 */
@ServiceImplementation
class FormSchemaServiceImpl(
    private val json: Json,
    private val formSchemaRepository: FormSchemaRepository,
    private val formSchemaPermissionRepository: FormSchemaPermissionRepository,
) : FormSchemaService {

    private val cacheById = ServiceCache<UUID, FormSchema>(
        "forms:schema:id",
        UUIDKeySerializer
    ) {
        formSchemaRepository.getById(it)
    }

    private val cacheByKey = ServiceCache<String, FormSchema>(
        "forms:schema:key",
        StringKeySerializer
    ) {
        formSchemaRepository.getByKey(it)
    }

    private val permissionCache = ServiceCache<UUID, List<EntityPermission>>(
        "forms:schema:permissions",
        UUIDKeySerializer,
        batchResolver = { keys, batch ->
            val allPerms = formSchemaPermissionRepository.getByFormSchemaIds(keys)
            val grouped = allPerms.groupBy { it.formSchemaId }
            keys.forEach { key ->
                batch.setData(key, grouped[key]?.map { it as EntityPermission } ?: emptyList())
            }
        }
    ) {
        formSchemaPermissionRepository.getByFormSchemaId(it).map { it as EntityPermission }
    }

    override suspend fun getAll(): List<FormSchema> = formSchemaRepository.getAll()

    override suspend fun getByType(type: FormSchemaType): List<FormSchema> = formSchemaRepository.getByType(type)

    override suspend fun getByKey(key: String): FormSchema? = cacheByKey.get(key)

    override suspend fun getById(id: UUID): FormSchema? = cacheById.get(id)

    override suspend fun save(input: FormSchemaInput): FormSchema {
        val profileMappingJson = input.profileMapping?.let {
            json.encodeToJsonElement(FormSchemaProfileMapping.serializer(), it)
        }
        val result = transaction {
            formSchemaRepository.upsert(
                type = input.type,
                key = input.key,
                name = input.name,
                description = input.description ?: "",
                schema = input.schema,
                uiSchema = input.uiSchema,
                configuration = input.configuration,
                profileMapping = profileMappingJson,
                public = input.public
            )
        }
        cacheByKey.remove(input.key)
        cacheById.remove(result.id)
        return result
    }

    override suspend fun setPublished(id: UUID, published: Boolean): FormSchema {
        val result = transaction {
            formSchemaRepository.setPublished(id, published)
        }
        cacheById.remove(id)
        cacheByKey.remove(result.key)
        return result
    }

    override suspend fun delete(id: UUID) {
        val existing = formSchemaRepository.getById(id)
        transaction {
            formSchemaRepository.delete(id)
        }
        cacheById.remove(id)
        if (existing != null) {
            cacheByKey.remove(existing.key)
        }
        permissionCache.remove(id)
    }

    override suspend fun getPermissions(entity: FormSchema): List<EntityPermission> {
        return permissionCache.get(entity.id) ?: emptyList()
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        permissionCache.addToBatch(batch)
    }

    override suspend fun addPermission(
        formSchemaId: UUID,
        groupId: UUID,
        action: PermissionAction
    ): EntityPermission {
        transaction {
            formSchemaPermissionRepository.add(formSchemaId, groupId, action)
        }
        permissionCache.remove(formSchemaId)
        return FormSchemaPermission(formSchemaId, groupId, action)
    }

    override suspend fun deletePermission(
        formSchemaId: UUID,
        groupId: UUID,
        action: PermissionAction
    ): EntityPermission {
        transaction {
            formSchemaPermissionRepository.delete(formSchemaId, groupId, action)
        }
        permissionCache.remove(formSchemaId)
        return FormSchemaPermission(formSchemaId, groupId, action)
    }
}
