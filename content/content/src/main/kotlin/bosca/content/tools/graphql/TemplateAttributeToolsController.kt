package bosca.content.tools.graphql

import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

@TypeController
class TemplateAttributeToolsController(
    private val toolService: TemplateAttributeToolService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<TemplateAttributeTools> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<TemplateAttributeTool> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return toolService.getAll()
    }

    @Field
    suspend fun get(authentication: AuthenticationContext, id: UUID): TemplateAttributeTool? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return toolService.get(id)
    }
}
