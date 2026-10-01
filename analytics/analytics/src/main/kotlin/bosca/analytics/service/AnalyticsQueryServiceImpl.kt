package bosca.analytics.service

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryInput
import bosca.analytics.model.QueryDefinitionPermission
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.QueryParameterType
import bosca.analytics.query.QueryParameterDeclaration
import bosca.analytics.repository.QueryDefinitionRepository
import bosca.analytics.repository.QueryParameterRepository
import bosca.analytics.repository.QueryPermissionRepository
import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.git.service.SourceRefService
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class AnalyticsQueryServiceImpl(
    private val queryRepository: QueryDefinitionRepository,
    private val parameterRepository: QueryParameterRepository,
    private val permissionRepository: QueryPermissionRepository,
    private val sourceRefService: ObjectProvider<SourceRefService>,
    private val resultCache: ObjectProvider<AnalyticsQueryResultCacheService>,
) : AnalyticsQueryService {

    private val queryPermissions = ServiceCache<UUID, List<EntityPermission>>(
        "querydefinition:permissions",
        UUIDKeySerializer,
        { keys, batch ->
            val permissionsByGroupId = permissionRepository.getPermissionsByIds(keys).groupBy { it.entityId }
            keys.forEach {
                batch.setData(it, permissionsByGroupId[it] ?: return@forEach)
            }
        }
    ) {
        permissionRepository.getPermissionsById(it)
    }

    override suspend fun getQueries(offset: Long, limit: Int): List<AnalyticsQuery> = queryRepository.getAll(offset, limit)

    override suspend fun getQueryById(id: UUID): AnalyticsQuery {
        return queryRepository.getById(id) ?: error("Query not found: $id")
    }

    override suspend fun getQueryByKey(key: String): AnalyticsQuery? {
        return queryRepository.getByKey(key)
    }

    override suspend fun getParameters(queryId: UUID): List<AnalyticsQueryParameter> {
        return parameterRepository.getParameters(queryId)
    }

    override suspend fun getParametersForQueries(queryIds: List<UUID>): List<AnalyticsQueryParameter> {
        return parameterRepository.getParametersForQueries(queryIds)
    }

    override suspend fun addQuery(query: AnalyticsQueryInput): AnalyticsQuery = transaction {
        validateRefreshInterval(query.refreshIntervalSeconds)
        val analyticsQuery = AnalyticsQuery(
            key = query.key,
            name = query.name,
            description = query.description,
            query = query.query,
            configuration = query.configuration,
            refreshIntervalSeconds = query.refreshIntervalSeconds,
        )
        val newQueryDefinition = queryRepository.add(analyticsQuery)
        query.parameters.forEachIndexed { index, parameter ->
            parameterRepository.addParameter(
                AnalyticsQueryParameter(
                    queryId = newQueryDefinition.id,
                    parameter = parameter.parameter.ifBlank { parameter.name },
                    name = parameter.name,
                    description = parameter.description,
                    type = parameter.type,
                    arrayType = parameter.arrayType ?: QueryParameterType.NONE,
                    defaultValue = parameter.defaultValue,
                    required = parameter.required,
                    sort = index
                )
            )
        }
        newQueryDefinition
    }

    override suspend fun editQuery(query: AnalyticsQueryInput): AnalyticsQuery = transaction {
        validateRefreshInterval(query.refreshIntervalSeconds)
        val analyticsQuery = AnalyticsQuery(
            id = query.id,
            key = query.key,
            name = query.name,
            description = query.description,
            query = query.query,
            configuration = query.configuration,
            refreshIntervalSeconds = query.refreshIntervalSeconds,
        )
        val updated = queryRepository.edit(analyticsQuery)
        // The edit may have changed the SQL, parameters, or caching configuration, so
        // any cached results may no longer match the definition. Drop them; the next
        // execution (or background refresh) repopulates the cache.
        resultCache.get().invalidate(analyticsQuery.id)
        parameterRepository.deleteParametersById(analyticsQuery.id)
        query.parameters.forEachIndexed { index, parameter ->
            parameterRepository.addParameter(
                AnalyticsQueryParameter(
                    queryId = analyticsQuery.id,
                    parameter = parameter.parameter.ifBlank { parameter.name },
                    name = parameter.name,
                    description = parameter.description,
                    type = parameter.type,
                    defaultValue = parameter.defaultValue,
                    required = parameter.required,
                    arrayType = parameter.arrayType ?: QueryParameterType.NONE,
                    sort = index
                )
            )
        }
        updated
    }

    override suspend fun applyGitSync(
        queryId: UUID,
        newSql: String,
        parameterDeclarations: List<QueryParameterDeclaration>?,
    ): AnalyticsQuery? = transaction {
        val existing = queryRepository.getById(queryId) ?: return@transaction null
        val sqlChanged = existing.query != newSql
        val existingParameters = if (parameterDeclarations != null) {
            parameterRepository.getParameters(queryId)
        } else {
            emptyList()
        }
        val parametersChanged = parameterDeclarations?.let {
            parameterDeclarationsChanged(existingParameters, it)
        } ?: false
        val definitionChanged = sqlChanged || parametersChanged
        val updated = if (definitionChanged) {
            val next = queryRepository.edit(existing.copy(query = newSql))
            resultCache.get().invalidate(queryId)
            next
        } else {
            existing
        }
        if (parameterDeclarations != null && parametersChanged) {
            parameterRepository.deleteParametersById(queryId)
            parameterDeclarations.forEachIndexed { index, declaration ->
                parameterRepository.addParameter(
                    AnalyticsQueryParameter(
                        queryId = queryId,
                        parameter = declaration.parameter,
                        name = declaration.name,
                        description = declaration.description,
                        type = declaration.type,
                        arrayType = declaration.arrayType,
                        defaultValue = declaration.defaultValue,
                        required = declaration.required,
                        sort = declaration.sort ?: index,
                    )
                )
            }
        }
        updated
    }

    override suspend fun deleteQueryById(id: UUID) {
        if (sourceRefService.exists) {
            sourceRefService.get().removeQuerySourceRef(id)
        }
        transaction {
            queryRepository.lockById(id) ?: return@transaction
            resultCache.get().invalidate(id)
            queryRepository.deleteById(id)
        }
    }

    override suspend fun getPermissions(entity: AnalyticsQuery): List<EntityPermission> {
        return queryPermissions.get(entity.id) ?: emptyList()
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        queryPermissions.addToBatch(batch)
    }

    private suspend fun removeFromCache(id: UUID) {
        queryPermissions.remove(id)
    }

    private fun validateRefreshInterval(intervalSeconds: Int?) {
        require(intervalSeconds == null || intervalSeconds >= MINIMUM_REFRESH_INTERVAL_SECONDS) {
            "Analytics query refresh interval must be at least $MINIMUM_REFRESH_INTERVAL_SECONDS seconds"
        }
    }

    private fun parameterDeclarationsChanged(
        existing: List<AnalyticsQueryParameter>,
        declarations: List<QueryParameterDeclaration>,
    ): Boolean {
        if (existing.size != declarations.size) return true
        return existing.zip(declarations).withIndex().any { (index, pair) ->
            val (parameter, declaration) = pair
            parameter.parameter != declaration.parameter ||
                parameter.name != declaration.name ||
                parameter.description != declaration.description ||
                parameter.type != declaration.type ||
                parameter.arrayType != declaration.arrayType ||
                parameter.defaultValue != declaration.defaultValue ||
                parameter.required != declaration.required ||
                parameter.sort != (declaration.sort ?: index)
        }
    }

    override suspend fun addPermission(permission: PermissionInput): EntityPermission {
        permissionRepository.addPermission(permission.entityId, permission.groupId, permission.action)
        removeFromCache(permission.entityId)
        return QueryDefinitionPermission(
            entityId = permission.entityId,
            groupId = permission.groupId,
            action = permission.action,
        )
    }

    override suspend fun deletePermission(permission: PermissionInput): EntityPermission {
        permissionRepository.deletePermission(permission.entityId, permission.groupId, permission.action)
        removeFromCache(permission.entityId)
        return QueryDefinitionPermission(
            entityId = permission.entityId,
            groupId = permission.groupId,
            action = permission.action,
        )
    }

    companion object {
        internal const val MINIMUM_REFRESH_INTERVAL_SECONDS = 60
    }
}
