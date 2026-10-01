package bosca.content.tools.graphql

import bosca.content.tools.model.TemplateAttributeTool
import bosca.content.tools.model.TemplateAttributeToolInput
import bosca.content.tools.service.TemplateAttributeToolService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

@TypeController
class TemplateAttributeToolsMutationController(
    private val toolService: TemplateAttributeToolService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<TemplateAttributeToolsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, tool: TemplateAttributeToolInput): TemplateAttributeTool {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return toolService.add(tool)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, tool: TemplateAttributeToolInput): TemplateAttributeTool? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return toolService.edit(id, tool)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        toolService.delete(id)
        return true
    }
}
