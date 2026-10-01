package bosca.ai.kit.agents.pipeline

import bosca.graphql.GraphQLService
import bosca.pipelines.service.PipelineService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import kotlinx.serialization.json.Json

/**
 * Contract-level services used by Kit's pipeline tools. Pipeline reads and validation use
 * [PipelineService]; boundary mutations and executions use [GraphQLService] so they retain the same
 * authorization and side effects as Studio (including schedule and Git synchronization).
 */
class PipelineServices(
    val pipelineService: PipelineService,
    val graphQLService: GraphQLService,
    val groupEvaluator: GroupEvaluator,
    val json: Json,
) {
    /** Pipeline authoring is the same administrator-only surface exposed by Studio. */
    fun verifyCanManage(authentication: AuthenticationContext) {
        groupEvaluator.verifyHasAdminGroup(authentication)
    }
}
