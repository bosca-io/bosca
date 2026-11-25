package bosca.content.image.graphql

import bosca.content.image.model.TemplateImageAttribute
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class TemplateImageAttributeController : GraphQLController<TemplateImageAttribute> {
    @Field fun key(value: TemplateImageAttribute) = value.key
    @Field fun name(value: TemplateImageAttribute) = value.name
    @Field fun description(value: TemplateImageAttribute) = value.description
    @Field fun relationship(value: TemplateImageAttribute) = value.relationship
    @Field fun list(value: TemplateImageAttribute) = value.list
    @Field fun targetSize(value: TemplateImageAttribute) = value.targetSize
    @Field fun defaultAspectRatio(value: TemplateImageAttribute) = value.defaultAspectRatio
}
