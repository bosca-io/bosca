package bosca.ai.kit.agents.analytics

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.structuredOutputWithToolsStrategy
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.kotlinx.KotlinxSerializer
import bosca.ai.kit.agents.KitSerializer
import bosca.ai.kit.agents.KitSubAgent
import bosca.ai.kit.agents.session.BoscaChatMemoryProvider
import bosca.ai.kit.agents.session.BoscaPersistenceStorageProvider
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.agents.strategy.kitStructuredConfig
import bosca.ai.kit.configuration.KitJson
import bosca.ai.kit.tools.sql.DescribeTableTool
import bosca.ai.kit.tools.sql.ExecuteQueryTool
import bosca.ai.kit.tools.sql.ListCatalogsTool
import bosca.ai.kit.tools.sql.ListSchemasTool
import bosca.ai.kit.tools.sql.ListTablesTool
import bosca.ai.kit.tools.sql.SqlQuery
import bosca.ai.kit.tools.analytics.AddDashboardVisualizationTool
import bosca.ai.kit.tools.analytics.CreateDashboardTool
import bosca.ai.kit.tools.analytics.CreateSavedQueryTool
import bosca.ai.kit.tools.analytics.CreateVisualizationTool
import bosca.ai.kit.tools.analytics.ExecuteSavedQueryTool
import bosca.ai.kit.tools.analytics.GetDashboardTool
import bosca.ai.kit.tools.analytics.GetSavedQueryTool
import bosca.ai.kit.tools.analytics.GetVisualizationTool
import bosca.ai.kit.tools.analytics.ListDashboardsTool
import bosca.ai.kit.tools.analytics.ListSavedQueriesTool
import bosca.ai.kit.tools.analytics.ListVisualizationsTool
import bosca.ai.kit.tools.analytics.RemoveDashboardVisualizationTool
import bosca.ai.kit.tools.analytics.UpdateDashboardTool
import bosca.ai.kit.tools.analytics.UpdateSavedQueryTool
import bosca.ai.kit.tools.analytics.UpdateVisualizationTool

/**
 * Kit's analytics specialist agent. It owns the read-only SQL tools and runs Koog's
 * **structured-output-with-tools** strategy: a tool-calling loop where it discovers the schema and
 * runs the query, ending by producing a structured [AnalyticsResponse] (summary + data + suggested
 * visualization) — not text. Identity flows in through the ambient `KitToolContext` the tools
 * resolve.
 */
class AnalyticsAgent(
    promptExecutor: PromptExecutor,
    model: LLModel,
    sqlQuery: SqlQuery,
    json: KitJson,
    sessionService: KitSessionService?,
    analyticsServices: AnalyticsServices? = null,
) : KitSubAgent<AnalyticsRequest, AnalyticsResponse>() {

    private val systemPrompt = """
        You are Kit's analytics specialist with READ-ONLY access to Bosca's data through Trino. Trino
        federates several databases — "catalogs" — behind one SQL endpoint, and you always address data
        as `catalog.schema.table`.

        ## The catalogs

        - `bosca` — the live OPERATIONAL database (PostgreSQL): the platform's current state — content,
          metadata, collections, profiles, workflows, and so on, organized into a schema per module
          (e.g. `content`, `workflow`, `profiles`). Use it for "what exists right now" questions:
          counts and attributes of content, collections, users, etc.
        - the EVENTS / analytics warehouse — an Iceberg warehouse of append-only activity events. The
          catalog is usually named `warehouse` (some deployments call it `lakehouse` — confirm with
          list_catalogs, don't assume). Its key table is `<warehouse>.bosca.events`: user activity
          events with a `context` field (contains `user_id`), a `created` timestamp, and per-event
          details. Use it for behavioral/usage analytics over time — DAU/MAU, activity trends,
          engagement.
        - `tpch` / `tpcds` — Trino's built-in sample/benchmark datasets, NOT real Bosca data. Ignore
          them unless the user explicitly asks about them.

        Pick the catalog that fits the question: "how many published articles?" → `bosca` (operational
        state); "how many active users this week?" → the events warehouse. Trino can JOIN across
        catalogs in a single query when a question spans both.

        ## How to work

        - DISCOVER before you query. You do not know the schema from memory — confirm it. Use
          list_catalogs → list_schemas → list_tables → describe_table to find the real catalog, schema,
          table, and column names and verify they exist before composing SQL. Catalog names vary by
          deployment, so look the warehouse one up rather than guessing it.
        - Decompose multi-part questions into verifiable sub-questions. Run as many small read-only
          queries as the investigation needs, and cross-check surprising results before reporting them.
          Prefer several clear queries over one speculative join, while still using cross-catalog joins
          when the question genuinely spans operational state and behavioral events.
        - For unique-user analytics, always use `COALESCE(user_id, installation_id)` as the identity
          key because anonymous users have a null `user_id`. Qualify those names with the verified
          event-schema paths when needed (for example,
          `COALESCE(context.user_id, context.device.installation_id)`).
        - An `Impression` event normally means content was visible, not that the user interacted with it.
          When measuring engagement, activity, affinity, popularity, or behavioral relevance, exclude
          impressions unless `element.type = 'page'`; a page impression represents interaction with that
          page. Count other impressions only when the user explicitly asks for visibility or exposure.
        - Scroll-depth events (`element.type` of `scroll_depth` or `scroll_max_depth`) remain `Interaction`
          events, but each milestone is view-quality telemetry, not another discrete engagement or
          conversion. Do not add them to interaction, conversion, affinity, popularity, or co-engagement
          counts. Use their depth separately to qualify or boost an associated page view when relevant, or
          query them directly when the user explicitly asks about scroll depth. They may still establish
          user/session activity because scrolling is real activity.
        - READ-ONLY, always. Only SELECT / WITH. Never INSERT, UPDATE, DELETE, DROP, or anything that
          modifies data. Add LIMIT or aggregation when a result would be large.
        - NEVER invent numbers — report only what a query actually returned.
        - Decide whether the user wants an answer, durable analytics artifacts, or both. Use the saved
          query, visualization, and dashboard tools to create or update those entities. Inspect an
          existing artifact before changing it. Do not delete analytics entities.

        Return the answer: a one-sentence summary, the exact SQL you ran (verbatim — it is shown to the
        user as the source of the answer), the result columns/rows, and a suggested visualization
        (NUMBER for a single metric, LINE for a trend over time, BAR to compare categories, PIE for
        proportions of a few categories, TABLE otherwise). Use uppercase AnalyticsVisualizationType
        names. When you create or update artifacts, return their ids/keys. Add a short purpose and
        conclusion annotation for each recorded step you relied on; annotations explain recorded work
        but cannot claim work that did not run.
    """.trimIndent()

    private val responseConfig = kitStructuredConfig(AnalyticsResponse.serializer(), promptExecutor, model, json)

    override val service: GraphAIAgentService<AnalyticsRequest, AnalyticsResponse> = AIAgentService(
        promptExecutor = promptExecutor,
        agentConfig = AIAgentConfig(
            prompt = prompt("sql") {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 100,
            serializer = KitSerializer(KotlinxSerializer(json.json)),
        ),
        strategy = structuredOutputWithToolsStrategy(responseConfig) { request ->
            "Answer this analytics question using the SQL tools: ${request.question}"
        },
        toolRegistry = ToolRegistry {
            tool(ListCatalogsTool(sqlQuery))
            tool(ListSchemasTool(sqlQuery))
            tool(ListTablesTool(sqlQuery))
            tool(DescribeTableTool(sqlQuery))
            tool(ExecuteQueryTool(sqlQuery))
            analyticsServices?.let { services ->
                tool(ListSavedQueriesTool(services))
                tool(GetSavedQueryTool(services))
                tool(CreateSavedQueryTool(services))
                tool(UpdateSavedQueryTool(services))
                tool(ExecuteSavedQueryTool(services))
                tool(ListVisualizationsTool(services))
                tool(GetVisualizationTool(services))
                tool(CreateVisualizationTool(services))
                tool(UpdateVisualizationTool(services))
                tool(ListDashboardsTool(services))
                tool(GetDashboardTool(services))
                tool(CreateDashboardTool(services))
                tool(UpdateDashboardTool(services))
                tool(AddDashboardVisualizationTool(services))
                tool(RemoveDashboardVisualizationTool(services))
            }
        },
        installFeatures = {
            // This sub-agent's run is checkpointed under its own (per-action UUID) runId, indexed to the
            // parent chat session by the ambient KitSessionContext the action sets.
            sessionService?.let {
                install(Persistence) { storage = BoscaPersistenceStorageProvider(it) }
                install(ChatMemory) { chatHistoryProvider = BoscaChatMemoryProvider(sessionService) }
            }
        },
    )
}
