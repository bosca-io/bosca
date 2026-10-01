package bosca.forms.graphql

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionInput
import bosca.forms.model.FormSubmissionStatus
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.forms.service.FormSchemaService
import bosca.forms.service.FormSubmissionService
import bosca.forms.service.SubmittedForm
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.ProfileType
import bosca.profile.profile.service.ProfileService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.ServerCall
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@TypeController
class FormsMutationController(
    private val formSubmissionService: FormSubmissionService,
    private val profileService: ProfileService,
    private val groupEvaluator: GroupEvaluator,
    private val formSchemaService: FormSchemaService,
    private val formSchemaPermissionEvaluator: FormSchemaPermissionEvaluator
) : GraphQLController<FormsMutation> {

    private val rateLimitCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(60))
        .build<String, AtomicInteger>()

    @Field
    suspend fun submit(
        call: ServerCall,
        authenticationContext: AuthenticationContext?,
        input: FormSubmissionInput
    ): SubmittedForm {
        if (authenticationContext?.principal() == null) {
            val clientIp = call.request.headers[bosca.server.HttpHeaders.XForwardedFor]
                ?.split(",")?.firstOrNull()?.trim()
                ?: call.request.origin.host
            val counter = rateLimitCache.get(clientIp) { AtomicInteger(0) }
            val count = counter.incrementAndGet()
            require(count <= MAX_SUBMISSIONS_PER_MINUTE) {
                "rate limit exceeded, please try again later"
            }
        }
        val schema = resolveFormSchema(input)
        if (!schema.public) {
            formSchemaPermissionEvaluator.verifyAllowed(authenticationContext, schema, PermissionAction.EXECUTE)
        }
        val resolvedInput = input.copy(formSchemaId = schema.id)
        val profileId = resolveProfileId(authenticationContext, resolvedInput)
        return formSubmissionService.submit(profileId, resolvedInput)
    }

    @Field
    suspend fun setSubmissionStatus(
        authenticationContext: AuthenticationContext,
        id: UUID,
        status: FormSubmissionStatus
    ): FormSubmission {
        groupEvaluator.verifyHasEditorGroup(authenticationContext)
        formSubmissionService.setStatus(id, status)
        return formSubmissionService.getById(id) ?: error("submission not found: $id")
    }

    @Field
    suspend fun deleteSubmission(
        authenticationContext: AuthenticationContext,
        id: UUID
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authenticationContext)
        formSubmissionService.delete(id)
        return true
    }

    private suspend fun resolveFormSchema(input: FormSubmissionInput): FormSchema {
        val id = input.formSchemaId
        if (id != null) {
            return formSchemaService.getById(id) ?: error("form schema not found: $id")
        }
        val key = input.formSchemaKey ?: error("either formSchemaId or formSchemaKey must be provided")
        return formSchemaService.getByKey(key) ?: error("form schema not found: $key")
    }

    private suspend fun resolveProfileId(
        authenticationContext: AuthenticationContext?,
        input: FormSubmissionInput
    ): UUID {
        val principal = authenticationContext?.principal()
        if (principal != null) {
            val profiles = profileService.getByPrincipal(principal.id)
            return (profiles.firstOrNull { it.isPrimary } ?: profiles.first()).id
        }
        val profileInput = input.profile ?: error("profile is required for anonymous submissions")
        return profileService.add(profileInput, ProfileType.GENERIC, null).id
    }

    companion object {
        private const val MAX_SUBMISSIONS_PER_MINUTE = 10
    }
}
