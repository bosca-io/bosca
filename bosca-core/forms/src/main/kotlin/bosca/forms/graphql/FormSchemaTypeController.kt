package bosca.forms.graphql

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaProfileMapping
import bosca.forms.model.FormSchemaType
import bosca.forms.model.FormSubmission
import bosca.forms.service.FormSchemaService
import bosca.forms.service.FormSubmissionService
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

/**
 * Type controller that resolves fields on the GraphQL `FormSchema` type.
 * Maps each Kotlin property to the corresponding GraphQL field.
 */
@TypeController
class FormSchemaTypeController(
    private val json: Json,
    private val formSchemaService: FormSchemaService,
    private val formSubmissionService: FormSubmissionService,
    private val groupEvaluator: GroupEvaluator,
    private val permissionEvaluator: FormSchemaPermissionEvaluator,
) : GraphQLController<FormSchema> {

    @Field
    fun id(formSchema: FormSchema) = formSchema.id

    @Field
    fun type(formSchema: FormSchema): FormSchemaType = formSchema.type

    @Field
    fun key(formSchema: FormSchema) = formSchema.key

    @Field
    fun name(formSchema: FormSchema) = formSchema.name

    @Field
    fun description(formSchema: FormSchema) = formSchema.description

    @Field
    fun schema(formSchema: FormSchema): JsonElement = formSchema.schema

    @Field
    fun uiSchema(formSchema: FormSchema): JsonElement = formSchema.uiSchema

    @Field
    fun configuration(formSchema: FormSchema): JsonElement? = formSchema.configuration

    @Field
    fun profileMapping(formSchema: FormSchema): FormSchemaProfileMapping? {
        val element = formSchema.profileMapping ?: return null
        return json.decodeFromJsonElement(FormSchemaProfileMapping.serializer(), element)
    }

    @Field
    fun version(formSchema: FormSchema) = formSchema.version

    @Field
    fun public(formSchema: FormSchema) = formSchema.public

    @Field
    fun published(formSchema: FormSchema) = formSchema.published

    @Field
    suspend fun permissions(authenticationContext: AuthenticationContext, formSchema: FormSchema): List<EntityPermission> {
        if (!permissionEvaluator.isAllowed(authenticationContext, formSchema, PermissionAction.MANAGE)) {
            return emptyList()
        }
        return formSchemaService.getPermissions(formSchema)
    }

    @Field
    suspend fun submissions(
        authenticationContext: AuthenticationContext,
        formSchema: FormSchema,
        limit: Int,
        offset: Long
    ): List<FormSubmission> {
        groupEvaluator.verifyHasEditorGroup(authenticationContext)
        return formSubmissionService.getByFormSchema(formSchema.id, offset, limit)
    }

    @Field
    fun created(formSchema: FormSchema): OffsetDateTime = formSchema.created

    @Field
    fun modified(formSchema: FormSchema): OffsetDateTime = formSchema.modified
}
