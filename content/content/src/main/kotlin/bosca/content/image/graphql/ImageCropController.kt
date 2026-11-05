package bosca.content.image.graphql

import bosca.content.image.model.Coordinates
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("ImageCrop")
class ImageCropController : GraphQLController<Coordinates> {
    @Field fun top(value: Coordinates) = value.top
    @Field fun left(value: Coordinates) = value.left
    @Field fun width(value: Coordinates) = value.width
    @Field fun height(value: Coordinates) = value.height
}
