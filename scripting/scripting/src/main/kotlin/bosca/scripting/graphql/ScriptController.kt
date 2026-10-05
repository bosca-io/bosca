@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.scripting.model.Script
import bosca.scripting.security.ScriptPermissionEvaluator
import bosca.scripting.service.ScriptService
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlin.uuid.ExperimentalUuidApi

@TypeController(type = "Script")
class ScriptController(
    private val scriptService: ScriptService,
    private val permissionEvaluator: ScriptPermissionEvaluator,
) : GraphQLController<Script> {

    @Field
    fun id(source: Script) = source.id

    @Field
    fun key(source: Script) = source.key

    @Field
    fun name(source: Script) = source.name

    @Field
    fun description(source: Script) = source.description

    @Field
    fun type(source: Script) = source.type

    @Field
    fun source(source: Script) = source.source

    @Field
    fun version(source: Script) = source.version

    @Field
    fun enabled(source: Script) = source.enabled

    @Field
    fun public(source: Script) = source.public

    @Field
    fun inputSchema(source: Script) = source.inputSchema

    @Field
    fun outputSchema(source: Script) = source.outputSchema

    @Field
    fun configuration(source: Script) = source.configuration

    @Field
    fun created(source: Script) = source.created

    @Field
    fun modified(source: Script) = source.modified

    @Field
    fun deletedAt(source: Script) = source.deletedAt

    @Field
    suspend fun permissions(authentication: AuthenticationContext?, source: Script): List<Permission> {
        if (!permissionEvaluator.isAllowed(authentication, source, PermissionAction.MANAGE)) {
            return emptyList()
        }
        return scriptService.getPermissions(source)
            .map { Permission(it.groupId, it.action) }
    }
}
