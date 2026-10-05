package bosca.forms.graphql

import bosca.forms.model.FormSchemaProfileMapping
import bosca.forms.model.FormSchemaProfileMappingAttribute
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.ProfileVisibility

/**
 * Resolves fields on the GraphQL `FormSchemaProfileMapping` type,
 * which describes how anonymous form submissions create profiles
 * from submitted field values.
 */
@TypeController
class FormSchemaProfileMappingTypeController : GraphQLController<FormSchemaProfileMapping> {

    @Field
    fun nameField(mapping: FormSchemaProfileMapping): String = mapping.nameField

    @Field
    fun visibility(mapping: FormSchemaProfileMapping): ProfileVisibility = mapping.visibility

    @Field
    fun attributes(mapping: FormSchemaProfileMapping): List<FormSchemaProfileMappingAttribute> = mapping.attributes
}
