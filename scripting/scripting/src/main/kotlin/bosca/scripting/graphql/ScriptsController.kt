@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptType
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

object Scripts

@TypeController
class ScriptsController(
    private val service: ScriptService,
    private val groups: GroupEvaluator
) : GraphQLController<Scripts> {

    @Field
    suspend fun all(authentication: AuthenticationContext, type: ScriptType?, includeDeleted: Boolean?): List<Script> {
        groups.verifyHasAdminGroup(authentication)
        val showDeleted = includeDeleted == true
        return when {
            type != null && showDeleted -> service.getByTypeIncludingDeleted(type)
            type != null -> service.getByType(type)
            showDeleted -> service.getAllIncludingDeleted()
            else -> service.getAll()
        }
    }

    @Field
    suspend fun script(authentication: AuthenticationContext, id: UUID): Script? {
        groups.verifyHasAdminGroup(authentication)
        return service.get(id)
    }

    @Field
    suspend fun scriptByKey(authentication: AuthenticationContext, key: String): Script? {
        groups.verifyHasAdminGroup(authentication)
        return service.getByKey(key)
    }

}
