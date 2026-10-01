package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.Permission
import bosca.security.service.SecurityService

@TypeController
class EntityPermissionController(
    private val securityService: SecurityService
) : GraphQLController<EntityPermission> {

    @Field
    fun groupId(permission: EntityPermission) = permission.groupId

    @Field
    fun action(permission: EntityPermission) = permission.action

    @Field
    suspend fun group(permission: EntityPermission): Group = securityService.getGroupById(permission.groupId)
}

@TypeController
class PermissionController(
    private val securityService: SecurityService
) : GraphQLController<Permission> {

    @Field
    fun groupId(permission: Permission) = permission.groupId

    @Field
    fun action(permission: Permission) = permission.action

    @Field
    suspend fun group(permission: Permission): Group = securityService.getGroupById(permission.groupId)
}