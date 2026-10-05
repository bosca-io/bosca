package bosca.forms.graphql

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionStatus
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.forms.service.FormSchemaService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

@TypeController
class FormSubmissionTypeController(
    private val formSchemaService: FormSchemaService,
    private val formSchemaPermissionEvaluator: FormSchemaPermissionEvaluator,
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator
) : GraphQLController<FormSubmission> {

    @Field
    fun id(formSubmission: FormSubmission): UUID = formSubmission.id

    @Field
    fun attributes(formSubmission: FormSubmission): JsonElement = formSubmission.attributes

    @Field
    fun status(formSubmission: FormSubmission): FormSubmissionStatus = formSubmission.status

    @Field
    fun created(formSubmission: FormSubmission): OffsetDateTime = formSubmission.created

    @Field
    fun modified(formSubmission: FormSubmission): OffsetDateTime = formSubmission.modified

    @Field
    suspend fun formSchema(
        authenticationContext: AuthenticationContext,
        formSubmission: FormSubmission
    ): FormSchema? {
        val schema = formSchemaService.getById(formSubmission.formSchemaId) ?: return null
        formSchemaPermissionEvaluator.verifyAllowed(authenticationContext, schema, PermissionAction.VIEW)
        return schema
    }

    @Field
    suspend fun profile(
        authenticationContext: AuthenticationContext,
        formSubmission: FormSubmission
    ): Profile {
        val profile = profileService.getById(formSubmission.profileId)
        profilePermissionEvaluator.verifyAllowed(authenticationContext, profile, PermissionAction.VIEW)
        return profile
    }
}
