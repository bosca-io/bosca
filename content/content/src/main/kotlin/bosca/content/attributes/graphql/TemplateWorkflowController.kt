package bosca.content.attributes.graphql

import bosca.content.attributes.model.TemplateWorkflow
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class TemplateWorkflowController : GraphQLController<TemplateWorkflow> {

    @Field
    fun autoRun(workflow: TemplateWorkflow) = workflow.autoRun
}