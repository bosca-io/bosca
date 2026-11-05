@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptType
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import bosca.scripting.security.ScriptPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

object Scripts

@TypeController
class ScriptsController(
    private val service: ScriptService,
    private val permissions: ScriptPermissionEvaluator
) : GraphQLController<Scripts> {

    @Field
    suspend fun all(authentication: AuthenticationContext, type: ScriptType?, includeDeleted: Boolean?, offset: Int?, limit: Int?): List<Script> {
        require((offset ?: 0) >= 0 && (limit == null || limit in 1..100)) { "Invalid script pagination" }
        val showDeleted = includeDeleted == true
        val scripts = when {
            type != null && showDeleted -> service.getByTypeIncludingDeleted(type)
            type != null -> service.getByType(type)
            showDeleted -> service.getAllIncludingDeleted()
            else -> service.getAll()
        }
        val allowed = permissions.isContentAllowed(authentication, scripts, PermissionAction.VIEW)
        return scripts.filterIndexed { index, _ -> allowed[index] }.drop(offset ?: 0).let { if (limit == null) it else it.take(limit) }
    }

    @Field
    suspend fun script(authentication: AuthenticationContext, id: UUID): Script? {
        return service.get(id)?.takeIf { permissions.isContentAllowed(authentication, it, PermissionAction.VIEW) }
    }

    @Field
    suspend fun scriptByKey(authentication: AuthenticationContext, key: String): Script? {
        return service.getByKey(key)?.takeIf { permissions.isContentAllowed(authentication, it, PermissionAction.VIEW) }
    }

}
