package bosca.profile.attribute.graphql

import bosca.forms.model.FormSchema
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.forms.service.FormSchemaService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext


@TypeController
class ProfileAttributeTypeController(
    private val formSchemaService: FormSchemaService,
    private val formSchemaPermissionEvaluator: FormSchemaPermissionEvaluator,
) : GraphQLController<ProfileAttributeType> {

    @Field
    fun id(type: ProfileAttributeType) = type.id

    @Field
    fun name(type: ProfileAttributeType) = type.name

    @Field
    fun description(type: ProfileAttributeType) = type.description

    @Field
    fun visibility(type: ProfileAttributeType) = type.visibility

    @Field(name = "protected")
    fun isProtected(type: ProfileAttributeType) = type.protected

    @Field
    fun formSchemaId(type: ProfileAttributeType) = type.formSchemaId

    @Field
    suspend fun formSchema(authentication: AuthenticationContext?, type: ProfileAttributeType): FormSchema? {
        val id = type.formSchemaId ?: return null
        val formSchema = formSchemaService.getById(id) ?: return null
        formSchemaPermissionEvaluator.verifyAllowed(authentication, formSchema, PermissionAction.VIEW)
        return formSchema
    }
}
