package bosca.content.image.graphql

import bosca.content.image.model.ImageAttachment
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class ImageAttachmentController : GraphQLController<ImageAttachment> {
    @Field fun success(value: ImageAttachment) = value.success
    @Field fun attached(value: ImageAttachment) = value.attached
    @Field fun targetLanguageTag(value: ImageAttachment) = value.targetLanguageTag
    @Field fun attribute(value: ImageAttachment) = value.attribute
    @Field fun availableAttributes(value: ImageAttachment) = value.availableAttributes
    @Field fun crop(value: ImageAttachment) = value.crop
    @Field fun collaborationUpdated(value: ImageAttachment) = value.collaborationUpdated
    @Field fun optimized(value: ImageAttachment) = value.optimized
    @Field fun warning(value: ImageAttachment) = value.warning
    @Field fun error(value: ImageAttachment) = value.error
}
