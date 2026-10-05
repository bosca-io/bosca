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

    ── When you work with WorkOps Specs, Requirements, or Tasks ──
    FIRST call `workops_spec_instructions` (no arguments) for the full mental model, lifecycle,
    document-authoring rules, and worked examples. The rules below are the short version, and they
    apply specifically to WorkOps planning/execution:

    1. KEEP STATUSES CURRENT. Transition a Spec/Requirement/Task to IN_PROGRESS *before* you start
       its work and to DONE (with a resolution) *immediately* after — never batch these. A Spec is
       DONE only when all its Requirements are DONE. A status that lags the real state is a data
       integrity bug; fix stale statuses when you find them.

    2. TRACK ALL WORK AS TASKS. Every unit of WorkOps work must exist as a Task. When you break work
       down, create a Task for EACH subtask with `parentTaskId` set. Untracked work is invisible work.

    3. THE SPEC DOCUMENT IS A LIVING PLAN. As you learn, change approach, drop, or add scope, update
       the spec body (and its Requirements) to match. Use comments for point-in-time notes; the body
       is the authoritative current plan.

    4. A REQUIREMENT'S LINKED TASK STARTS EMPTY. Creating a Requirement snapshots its (initially
       empty) document into the linked Task once, and nothing re-syncs it afterward. After you write
       the Requirement's body, also push it to the linked Task via
       `workops_task action=update id=<taskId> descriptionMarkdown=<body>` — otherwise the Task page
       is blank.

    ── When you author a document body (spec, requirement, RFC, guide, design note) ──
    5. DOCUMENT BODIES ARE RICH DOCUMENTS. Write them via `content_metadata` using `documentContent`
       or `markdownContent` (preferred). `textContent`/`jsonContent`/`filePath` bypass the Document
       model and must not be used for document bodies. (They remain the CORRECT choice for non-document
       content — raw text blobs, opaque JSON, and image/video/file uploads — so use them freely there.)

    6. LET THE SERVER MINT DOCUMENTS. On create, pass `name` and let auto-create build and template-
       bind the backing document (which also tags it with the right document `type`). Only supply your
       own `metadataId` when attaching a document you have deliberately prepared.
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
