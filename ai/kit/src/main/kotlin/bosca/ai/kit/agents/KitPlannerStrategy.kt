package bosca.ai.kit.agents

import ai.koog.agents.core.planner.AIAgentPlannerStrategy
import ai.koog.agents.planner.goap
import ai.koog.prompt.executor.model.PromptExecutor
import bosca.ai.kit.agents.actions.ChatAction
import bosca.ai.kit.agents.actions.ClarifyAction
import bosca.ai.kit.agents.actions.CreatePipelineAction
import bosca.ai.kit.agents.actions.DescribeMetadataAction
import bosca.ai.kit.agents.actions.FetchScriptureAction
import bosca.ai.kit.agents.actions.KitAction
import bosca.ai.kit.agents.actions.ProvideAnalyticsAction
import bosca.ai.kit.agents.actions.ProvideReadingTimeAction
import bosca.ai.kit.agents.actions.ProvideScriptureAction
import bosca.ai.kit.agents.actions.ProvideTopicsAction
import bosca.ai.kit.agents.actions.GraphQLAction
import bosca.ai.kit.agents.actions.ImageAction
import bosca.ai.kit.agents.actions.RouteAction
import bosca.ai.kit.agents.actions.SaveDocumentAction
import bosca.ai.kit.agents.actions.ScriptAction
import bosca.ai.kit.agents.actions.WriteDocumentAction
import bosca.ai.kit.agents.analytics.AnalyticsServices
import bosca.ai.kit.agents.image.ImageServices
import bosca.ai.kit.agents.pipeline.PipelineServices
import bosca.ai.kit.agents.script.ScriptServices
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.sql.SqlQuery
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.graphql.GraphQLService
import kotlinx.serialization.json.Json

/**
 * Kit as a **GOAP planner**. Kit holds no action logic of its own: each capability is a
 * self-contained [KitAction] that understands the shared [KitState] and registers itself onto the
 * planner via its `action()` member-extension. Adding a capability means **allocating a class here**
 * — not editing a monolithic planner block. The A* planner then sequences whichever actions are
 * applicable until the `done` goal ([KitState.isReplyable]) is reached, re-planning from real state
 * after each step so the WRITE/QUERY/CHAT branch is decided at run time by [RouteAction].
 */
internal class KitPlannerStrategy(
    promptExecutor: PromptExecutor,
    models: KitModels,
    bibleService: BibleService,
    metadataService: MetadataService,
    documentService: DocumentService,
    collectionService: CollectionService,
    sessionService: KitSessionService,
    sqlQuery: SqlQuery,
    analyticsServices: AnalyticsServices?,
    scriptServices: ScriptServices,
    imageServices: ImageServices,
    graphQLService: GraphQLService,
    json: KitJson,
    pipelineServices: PipelineServices? = null,
) {
    /** Kit's allocated actions. Add a capability by allocating its action here. */
    private val actions: List<KitAction> = buildList {
        add(RouteAction(promptExecutor, models.route, json, sessionService))
        add(FetchScriptureAction(bibleService))
        add(ProvideScriptureAction(bibleService))
        add(WriteDocumentAction(promptExecutor, models.write, bibleService, json, sessionService))
        add(SaveDocumentAction(metadataService))
        add(ProvideAnalyticsAction(promptExecutor, models.analytics, sqlQuery, json, sessionService, analyticsServices))
        add(DescribeMetadataAction(promptExecutor, models.describe, documentService, json, sessionService))
        add(ProvideTopicsAction(promptExecutor, models.topics, documentService, collectionService, json, sessionService))
        add(ProvideReadingTimeAction(promptExecutor, models.readingTime, documentService, json, sessionService))
        add(ScriptAction(promptExecutor, models.script, scriptServices, json, sessionService))
        pipelineServices?.let { add(CreatePipelineAction(promptExecutor, models.pipeline, it, json, sessionService)) }
        add(ImageAction(promptExecutor, models.image, imageServices, json, sessionService))
        add(GraphQLAction(promptExecutor, models.graphql, graphQLService, json, sessionService))
        add(ChatAction(promptExecutor, models.chat, json, sessionService))
        add(ClarifyAction())
    }

    /** Build Kit's GOAP planner strategy: every action registers itself, then the shared goal. */
    val strategy: AIAgentPlannerStrategy<KitRequest, KitResponse> by lazy {
        goap("kit", { input: KitRequest -> KitState(request = input) }) {
            for (action in actions) with(action) { action() }
            goal(
                name = "done",
                condition = { it.isReplyable },
            )
        }
    }
}
