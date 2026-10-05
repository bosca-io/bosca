package bosca.forms.graphql

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaType
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.forms.service.FormSchemaService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Query controller exposing form schema lookups. Maps to the
 * GraphQL `FormSchemas` type defined in forms.graphqls.
 */
@TypeController
class FormSchemasController(
    private val formSchemaService: FormSchemaService,
    private val permissionEvaluator: FormSchemaPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<FormSchemas> {

    @Field
    suspend fun all(authenticationContext: AuthenticationContext): List<FormSchema> {
        groupEvaluator.verifyHasEditorGroup(authenticationContext)
        return formSchemaService.getAll().filter {
            permissionEvaluator.isAllowed(authenticationContext, it, PermissionAction.LIST)
        }
    }

    @Field
    suspend fun byType(authenticationContext: AuthenticationContext, type: FormSchemaType): List<FormSchema> {
        groupEvaluator.verifyHasEditorGroup(authenticationContext)
        return formSchemaService.getByType(type).filter {
            permissionEvaluator.isAllowed(authenticationContext, it, PermissionAction.LIST)
        }
    }

    @Field
    suspend fun byKey(authenticationContext: AuthenticationContext?, key: String): FormSchema? {
        val schema = formSchemaService.getByKey(key) ?: return null
        permissionEvaluator.verifyAllowed(authenticationContext, schema, PermissionAction.VIEW)
        return schema
    }

    @Field
    suspend fun byId(authenticationContext: AuthenticationContext?, id: UUID): FormSchema? {
        val schema = formSchemaService.getById(id) ?: return null
        permissionEvaluator.verifyAllowed(authenticationContext, schema, PermissionAction.VIEW)
        return schema
    }
}
