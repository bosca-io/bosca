package bosca.communications.graphql

import bosca.communications.model.BmlMessageHostedProject
import bosca.communications.model.BmlMessageTemplateInfo
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController(type = "BmlMessageHostedProject")
class BmlMessageHostedProjectController : GraphQLController<BmlMessageHostedProject> {

    @Field fun project(source: BmlMessageHostedProject): String = source.project
    @Field fun activeVersion(source: BmlMessageHostedProject): String = source.activeVersion
    @Field fun pinnedVersion(source: BmlMessageHostedProject): String? = source.pinnedVersion
    @Field fun templates(source: BmlMessageHostedProject): List<BmlMessageTemplateInfo> = source.templates
}
