package bosca.forms.graphql

import bosca.forms.model.FormSchemaProfileMappingAttribute
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves fields on the GraphQL `FormSchemaProfileMappingAttribute` type,
 * which maps a single form field to a profile attribute type.
 */
@TypeController
class FormSchemaProfileMappingAttributeTypeController : GraphQLController<FormSchemaProfileMappingAttribute> {

    @Field
    fun typeId(attr: FormSchemaProfileMappingAttribute): String = attr.typeId

    @Field
    fun field(attr: FormSchemaProfileMappingAttribute): String = attr.field

    @Field
    fun attributeKey(attr: FormSchemaProfileMappingAttribute): String = attr.attributeKey
}
