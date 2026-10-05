package bosca.forms.graphql

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaInput
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.forms.service.FormSchemaService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Mutation controller for form schema CRUD and permission operations.
 * Maps to the GraphQL `FormSchemasMutation` type in forms.graphqls.
 */
@TypeController
class FormSchemasMutationController(
    private val formSchemaService: FormSchemaService,
    private val permissionEvaluator: FormSchemaPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<FormSchemasMutation> {

    @Field
    suspend fun save(
        authenticationContext: AuthenticationContext,
        input: FormSchemaInput,
    ): FormSchema {
        groupEvaluator.verifyHasEditorGroup(authenticationContext)
        val existing = formSchemaService.getByKey(input.key)
        if (existing != null) {
            permissionEvaluator.verifyAllowed(authenticationContext, existing, PermissionAction.EDIT)
        }
        return formSchemaService.save(input)
    }

    @Field
    suspend fun setPublished(
        authenticationContext: AuthenticationContext,
        id: UUID,
        published: Boolean,
    ): FormSchema {
        val schema = formSchemaService.getById(id) ?: error("form schema not found: $id")
        permissionEvaluator.verifyAllowed(authenticationContext, schema, PermissionAction.EDIT)
        return formSchemaService.setPublished(id, published)
    }

    @Field
    suspend fun delete(
        authenticationContext: AuthenticationContext,
        id: UUID,
    ): Boolean {
        val schema = formSchemaService.getById(id) ?: error("form schema not found: $id")
        permissionEvaluator.verifyAllowed(authenticationContext, schema, PermissionAction.DELETE)
        formSchemaService.delete(id)
        return true
    }

    @Field
    suspend fun addPermission(
        authenticationContext: AuthenticationContext,
        permission: PermissionInput,
    ): EntityPermission {
        val schema = formSchemaService.getById(permission.entityId)
            ?: error("form schema not found: ${permission.entityId}")
        permissionEvaluator.verifyAllowed(authenticationContext, schema, PermissionAction.MANAGE)
        return formSchemaService.addPermission(permission.entityId, permission.groupId, permission.action)
    }

    @Field
    suspend fun deletePermission(
        authenticationContext: AuthenticationContext,
        permission: PermissionInput,
    ): EntityPermission {
        val schema = formSchemaService.getById(permission.entityId)
            ?: error("form schema not found: ${permission.entityId}")
        permissionEvaluator.verifyAllowed(authenticationContext, schema, PermissionAction.MANAGE)
        return formSchemaService.deletePermission(permission.entityId, permission.groupId, permission.action)
    }
}
