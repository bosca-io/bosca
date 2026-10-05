package bosca.forms.graphql

import bosca.forms.model.FormSchemaType
import bosca.forms.service.SubmittedForm
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/**
 * Resolves fields on the lightweight [SubmittedForm] type returned
 * by the submit mutation, exposing only the submission identifier
 * and the originating form schema type.
 */
@TypeController
class SubmittedFormTypeController : GraphQLController<SubmittedForm> {

    @Field
    fun id(submittedForm: SubmittedForm): UUID = submittedForm.id

    @Field
    fun type(submittedForm: SubmittedForm): FormSchemaType = submittedForm.type
}
