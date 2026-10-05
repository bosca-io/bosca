@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.localization.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.localization.model.LocalizationProjectPermission
import bosca.security.model.Group
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.UUID

/** Resolves field-level data on [LocalizationProjectPermission]. */
@TypeController
class LocalizationProjectPermissionController(
    private val securityService: SecurityService
) : GraphQLController<LocalizationProjectPermission> {

    @Field
    fun projectId(permission: LocalizationProjectPermission): UUID = permission.projectId

    @Field
    fun action(permission: LocalizationProjectPermission): PermissionAction = permission.action

    @Field
    suspend fun group(permission: LocalizationProjectPermission): Group? =
        try {
            securityService.getGroupById(permission.groupId)
        } catch (_: NoSuchElementException) {
            null
        }
}
