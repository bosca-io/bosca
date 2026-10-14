package bosca.cli.mcp

import bosca.cli.BoscaCliCommand
import bosca.cli.api.AnalyticsApi
import bosca.cli.api.ContentApi
import bosca.cli.api.KitApi
import bosca.cli.api.WorkOpsApi
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Server-level instructions surfaced to the MCP client during the
 * `initialize` handshake (the SDK echoes this back in the session's
 * capabilities). Unlike per-tool descriptions — which the model only
 * sees when it considers that specific tool — these are delivered once,
 * up front. The charter therefore covers the whole server (WorkOps AND
 * Content), and deliberately scopes the WorkOps discipline to "when you
 * are working with Specs/Requirements/Tasks" so it does not bias purely
 * content-, media-, or (future) other-domain sessions. The full WorkOps
 * manual lives in the `workops_spec_instructions` tool (single source of
 * truth) to avoid drift.
 */
internal val MCP_SERVER_INSTRUCTIONS = """
    This is the Bosca MCP server. It exposes several independent tool families:
      • Kit — conversational access to Bosca's AI assistant for analytics questions, image generation,
        content work, and other platform tasks, including downloading generated assets.
      • WorkOps planning & tracking — portfolios, programs, projects, specs, requirements, tasks,
        sprints, boards, comments, links, worklogs, search, notifications.
      • Content management — metadata (documents, guides, data, media), collections, templates,
        supplementary attachments, relationships. These are first-class and usable on their own,
        not just as spec/requirement backing documents.
      • Analytics — execute and manage saved queries, visualization definitions, and dashboards.
    Use whichever family the task needs; nothing here requires you to involve WorkOps when you are
    only doing content (or other) work.

    When working with WorkOps, FIRST call workops_spec_instructions with the projectId or projectKey
    to load the effective editable guides and executable policy. No arguments loads platform defaults.
    Reload when resuming work; Studio → WorkOps → Settings → Guidance owns these instructions.
    workops_plan context, reviews, next_action, and verification use the current project configuration.
    Reviews and validation record its revision and report stale evidence after guidance changes.
    Tool schemas describe API mechanics; configured guides describe the workflow. Never invent evidence.

    ── When you author a document body (spec, requirement, RFC, guide, design note) ──
    5. DOCUMENT BODIES ARE RICH DOCUMENTS. Write them via `content_metadata` using `documentContent`
       or `markdownContent` (preferred). `textContent`/`jsonContent`/`filePath` bypass the Document
       model and must not be used for document bodies. (They remain the CORRECT choice for non-document
       content — raw text blobs, opaque JSON, and image/video/file uploads — so use them freely there.)

    6. LET THE SERVER MINT DOCUMENTS. On create, pass `name` and let auto-create build and template-
       bind the backing document (which also tags it with the right document `type`). Only supply your
       own `metadataId` when attaching a document you have deliberately prepared.

    ── When you answer user, session, or engagement questions from analytics events ──
    7. USE THE STANDARD METRIC QUERIES. Execute them by key with `startDate`/`endDate` instead of writing
       new SQL: `daily-active-users`, `unique-sessions-per-day`, and `monthly-users-and-engagement`
       (monthly active users and average engagement time per active user; use whole months).

    8. KEEP THEIR DEFINITIONS WHEN YOU WRITE NEW EVENT SQL.
       • An active user has a `Session` event with `extras.start = 'true'`. Identity is `user_id`, else
         `installation_id`; signed-out activity counts as the installation's user when exactly one user
         signed in on it within the same reporting period.
       • Exclude the bot agent pattern and desktop Chrome agents with a full build number
         (`Chrome/N.0.<non-zero>` on Windows, Mac, or Linux desktop), which are scrapers.
       • Count sessions as distinct `session_id`, never start events: each page load re-sends a start
         for the session it continues. Sessions end after 5 minutes without activity, so session counts
         run higher than tools that use a 30-minute timeout.
       • Engagement time is estimated from gaps between consecutive events in a session, each capped at
         5 minutes; audio and video progress events count playback as activity. The estimate shifts when
         event instrumentation changes, so compare only periods with the same instrumentation.
       • `created` is the device time of the event, stored in UTC.
""".trimIndent()

/**
 * Runs the Bosca CLI as a STDIO-based MCP server, exposing workops
 * operations as structured tools that AI assistants can discover and
 * invoke directly. Reads JSON-RPC from stdin, writes to stdout.
 */
class McpServerCommand : BoscaCliCommand(name = "mcp-server") {
    override fun help(context: Context) = "Run as an MCP (Model Context Protocol) server over STDIO for AI tool integration"

    private val url by option(
        "--url", "-u",
        envvar = "BOSCA_ENDPOINT",
        help = "Bosca GraphQL endpoint URL"
    )

    private val token by option(
        "--token", "-t",
        envvar = "BOSCA_TOKEN",
        help = "Bearer token for authentication"
    )

    private val username by option(
        "--username",
        envvar = "BOSCA_USERNAME",
        help = "Authentication username"
    )

    private val password by option(
        "--password",
        envvar = "BOSCA_PASSWORD",
        help = "Authentication password"
    )

    override fun run() = runBlocking {
        // BoscaAuth's per-request token provider lazily refreshes the session for
        // the life of this long-running server and persists each rotation to the
        // CLI config — so the token never expires mid-session and no background
        // refresh loop is required.
        val network = networkClient(url, token, username, password)
        val api = WorkOpsApi(network)
        val contentApi = ContentApi(network)
        val analyticsApi = AnalyticsApi(network)
        val kitApi = KitApi(network)

        val server = Server(
            Implementation("bosca-mcp", "1.0.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
            MCP_SERVER_INSTRUCTIONS,
        )

        McpToolRegistrar.registerAll(server, api, contentApi)
        ContentToolRegistrar.registerAll(server, contentApi)
        AnalyticsToolRegistrar.registerAll(server, analyticsApi)
        KitToolRegistrar.registerAll(server, kitApi)
        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered(),
        )

        val session = server.createSession(transport)

        val closeJob = Job()
        transport.onClose {
            closeJob.complete()
        }
        closeJob.join()
    }
}
