package bosca.communications.graphql

import bosca.communications.model.BmlMessageTemplateInfo
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import kotlinx.serialization.json.JsonElement

@TypeController(type = "BmlMessageTemplateInfo")
class BmlMessageTemplateInfoController : GraphQLController<BmlMessageTemplateInfo> {

    @Field fun key(source: BmlMessageTemplateInfo): String = source.key
    @Field fun samplePayload(source: BmlMessageTemplateInfo): JsonElement? = source.samplePayload
    @Field fun payloadSchema(source: BmlMessageTemplateInfo): JsonElement? = source.payloadSchema
    @Field fun supportsEmail(source: BmlMessageTemplateInfo): Boolean = source.supportsEmail
    @Field fun supportsPush(source: BmlMessageTemplateInfo): Boolean = source.supportsPush
}
