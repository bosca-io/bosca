package bosca.communications.graphql

import bosca.communications.model.EmailPreview
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController(type = "EmailPreview")
class EmailPreviewController : GraphQLController<EmailPreview> {

    @Field fun project(source: EmailPreview): String = source.project
    @Field fun templateKey(source: EmailPreview): String = source.templateKey
    @Field fun version(source: EmailPreview): String = source.version
    @Field fun subject(source: EmailPreview): String = source.subject
    @Field fun html(source: EmailPreview): String = source.html
    @Field fun text(source: EmailPreview): String = source.text
}
