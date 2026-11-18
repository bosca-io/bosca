# Kit

Kit's outbound ACP integration lives in `harness/acp`. `kitAcpStrategy` runs one
remote turn through Koog, using the ACP Kotlin client. The harness owns inference
and tool selection. The strategy does not call a local model or retry prompts.
`KitChatDispatcher` accepts durable turns; the Runner drives the strategy through
one warm runtime per conversation. Idle runtimes archive into private object
storage and restore into a new generation when another message arrives. See the
[runtime configuration and lifecycle](runtime/README.md).

## ACP connection contract

- `AcpTransportFactory` separates runtime discovery from protocol handling.
  `AcpWebSocketTransportFactory` uses a deployment-selected `ws`/`wss` endpoint
  ending in `/acp`, with a bearer token resolved at connection time.
- The WebSocket carries one JSON-RPC message per text frame. This is a Bosca
  transport, separate from ACP's standard local stdio transport. Binary and
  malformed frames fail the connection. Outgoing messages use a bounded queue.
- Initialization negotiates ACP v1 and advertises no Runner filesystem or
  terminal callbacks. An optional authentication method must be advertised by
  the harness. Provider credentials are not passed in authentication metadata.
- Session creation/restoration supplies the current workspace and selected MCP
  connections. HTTP/SSE connections require advertised support. Session IDs are
  opaque. Restoration prefers advertised resume, then load, otherwise reports
  reconstruction. A failed advertised restore propagates its error.
- A reconstructed session needs current, permitted canonical context. The caller
  must record the continuity boundary and must not replay old side effects.
- Events reach the caller before the strategy accepts the terminal result.
  The caller sends `session.cancel()` and continues collecting to observe the
  confirmed terminal response. Cancelling the collector alone does not confirm
  remote cancellation. Connection loss raises `AcpConnectionException` and needs
  reconciliation by the durable owner; parent coroutine cancellation is preserved.

The caller owns durable message/turn identity, principal authorization, fenced
ownership, event persistence, and recovery. A Koog graph checkpoint does not
prove that an uncertain remote prompt can be safely repeated.

External MCP connections come from the existing registry and Agent/tool assignments.
Required and optional selection, runtime secret references, capability checks and
readiness probes are described in the [runtime configuration](runtime/README.md#approved-mcp-connections).
Changes take effect through generation retirement; warm sessions keep their prepared
connections and restored generations resolve current credentials.

Model and reasoning controls use the [private policy and live session capabilities](runtime/README.md#model-and-reasoning-choices). Their native keys stay inside the Codex ACP adapter.

## Tool results

The pinned Codex ACP adapter places MCP results in `rawOutput.result.content`.
The transcript extracts standard MCP text, image, audio, resource and resource-link
blocks from that envelope into the existing ACP tool content. Raw arguments,
opaque result fields, `_meta`, and assistant-only blocks are excluded from display.
Unsupported blocks produce a visible notice. A resource and its identical text
copy display once, and repeated updates replace the same tool result.

Bosca's CLI attaches `bosca://content/metadata/<uuid>` resource links to successful
content item operations and links saved analytics query, visualization and dashboard
results. Query execution embeds the existing bounded JSON rows in an
`application/json` resource at `bosca://analytics/queries/<uuid>/results` or
`bosca://analytics/queries/by-key/<encoded-key>/results`. It does not fetch the saved
query definition. Callers with query VIEW access receive the SQL actually sent to
JDBC, positional bound values, execution time and duration with their rows. Execute-only
callers receive rows and cache status with no SQL or parameter evidence. Cache hits
retain the original execution snapshot, including resolved relative dates.

Studio renders these query resources with its existing table visualization and
shows row count, truncation, cache freshness and available SQL/parameter evidence. Known artifact URIs link to
Studio editors; rendering does not fetch a resource. Ordinary MCP images and
resources retain their typed content in both live and recovered turn snapshots.

`bosca_graphql` restores generic live-schema discovery and caller-authorized
query/mutation execution through the CLI's existing HTTP transport. Roots, type
details and paginated schema search respect the server's introspection policy;
production discovery requires administrator membership. Execution preserves
variables, operation selection, data and error messages, including partial data.
The standard embedded JSON result renders in Studio's read-only JSON viewer.
MCP cancellation interrupts the matching network request without repeating it or
claiming that completed side effects were undone. See the
[GraphQL tool contract](../../cli/README.md#graphql-through-mcp).

The CLI's `content_scripture` tool provides translation discovery, book and chapter
listing, reference resolution and exact passage retrieval. Passage reads use the
content API's `AI_CONTEXT` checks and return the selected edition, reference,
component tree and attribution. The tool directs the harness to quote only
retrieved text and preserve its copyright and citation. It reports missing or
denied passages without substituting another translation.

Document writing uses `content_metadata`: `create` saves an inline typed Content
tree through the existing content add mutation, and `get_document` returns its
body and template. `set_content` supports typed edits or server-side Markdown
conversion. Headings, inline marks and quoted text remain structured content.
Creation leaves readiness and publication to explicit content operations. The CLI
returns saved content links and retains a created ID if a later visibility update
fails. See the [document tool contract](../../cli/README.md#documents-through-mcp).

The `bosca_script` tool exposes script discovery, reads, validation, definition
management, enable/disable, deletion and execution. Unsaved compilation runs on
the scripting worker when needed and never invokes the compiled script. Edits
require the previously read version, and create/edit previews do not save.
The profile must explicitly include the needed script scopes; script permissions
remain enforced by the API. These operations do not replace native permission
callbacks or establish a separate approval policy for Bosca writes.

The CLI's `content_image` tool generates or edits an image through Bosca's existing
Gemini image configuration and saves the result as a new content item. Edits check
source content VIEW access and preserve the source. The API checks `content:edit`
and destination collection EDIT access (`collections:edit` for scoped tokens), or
editor membership for root creation. Preview reads use the caller's ordinary
content access. Results include the stored content link and, when readable, an MCP
image block. A failed preview retains the saved ID; use `get` to retry that read.
Generation requests are not automatically retried. Attachment and publication are
separate content operations; generation leaves the image uploaded and unready.

`content_image action=attach` uses the existing content image service to select an
image attribute from the target's document, data, or collection template. It applies
the template's relationship, list behavior, target size, and crop settings, with
collection language variants supported. Missing or ambiguous attribute selection
returns choices. The caller needs source content VIEW and target EDIT access;
attachment does not publish.

Image generation and editing use `KitImageGenerationService`, which backs the
content image API. Configure `google.genai.account` and `google.genai.image.model`
on Bosca. Missing image credentials fail image requests without preventing startup.
Provider credentials stay on Bosca; the harness uses its scoped Bosca token.

## Conversation capability mapping

The conversation dispatcher queues harness turns. These are the current paths
for the previous planner's capabilities:

| Capability | Current path |
| --- | --- |
| Routing, chat and clarification | Harness inference through `kitAcpStrategy` and the existing conversation stream |
| Writing and saving documents | `content_metadata` typed document reads/writes and Markdown conversion |
| Exact Scripture retrieval | `content_scripture`, with the selected edition and source-use checks |
| Warehouse investigation and analytics artifacts | `analytics_sql`, `analytics_query`, `analytics_visualization` and `analytics_dashboard` |
| Script management and execution | `bosca_script` through the scoped API and existing scripting worker |
| Pipeline authoring and execution | `bosca_pipeline` through existing validation, dry-run and durable run services |
| Image generation, editing and attachment | `content_image` through the shared image services |
| Generic platform operations | `bosca_graphql`; live schema discovery follows the server's introspection policy |
| Bounded child tasks | `bosca_delegate`; opt-in policy, isolated child sessions and durable result waits |
| Descriptions, topics and reading-time suggestions | Document reads plus harness inference; `content_collection action=find` retrieves permitted topic candidates by `type=Topic` |

Stored text, analytics and `graphql-result` history rows remain displayable in
Studio alongside harness turns. The GraphQL viewer preserves the stored JSON,
type label and SDL, including null/scalar results and partial errors. It reads
the saved event without replaying its operation or rewriting the history.

Direct metadata enrichment, localization and release-note generation remain
separate Koog consumers in `metadata/KitMetadataAIService.kt`,
`localization/KitLocalizationAIService.kt` and
`releasenotes/KitReleaseNotesAIService.kt`. Their service contracts are independent
of the conversation dispatcher. Each runs as a one-shot structured request without
Koog checkpoints or chat memory. The previous conversation planner, its sub-agents
and their in-process Kotlin tools have been removed; the CLI tools above provide
those capabilities. Kit records each conversation's user and assistant turns in
chat history as Koog messages, and no native checkpoint is made from a Koog
snapshot. Provider-backed output qualification remains a separate check.

## Validation

From the workspace root:

```bash
./gradlew :ai:kit:test --tests 'bosca.ai.kit.harness.acp.*'
./gradlew :cli:test --tests 'bosca.cli.mcp.*'
./gradlew :cli:nativeCompile
python3 cli/scripts/test-harness-mcp.py cli/build/native/nativeCompile/bosca
python3 cli/scripts/test-kit-protocols.py cli/build/native/nativeCompile/bosca
python3 cli/scripts/test-kit-protocols.py cli/build/native/nativeCompile/bosca --cancellation
python3 cli/scripts/test-kit-protocols.py cli/build/native/nativeCompile/bosca --cancellation --outcome COMPLETED
python3 cli/scripts/test-kit-protocols.py cli/build/native/nativeCompile/bosca --cancellation --outcome INTERRUPTED
```

The synthetic harness uses a real authenticated WebSocket and independent
JSON-RPC handling. Tests exercise the real Koog strategy and ACP client, including
streams, approvals, cancellation, protocol failures, restoration, and MCP
configuration. The CLI smoke test uses an isolated local GraphQL fixture and
temporary credentials; it does not access a deployed Bosca environment.
The inbound protocol smoke additionally exercises native MCP, ACP and HTTP A2A
against a local GraphQL fixture containing completed harness results, including
resources, file diffs, inline images/audio and binary attachments.
Cancellation modes send actual MCP, ACP and A2A cancellation messages. The fixture
acknowledges the mutation before confirming a terminal outcome, including cases
where completion or interruption wins the race with cancellation.

Dependency versions are pinned in the shared Gradle catalog: Koog 1.3.0, its
1.3.0-beta companion modules, and ACP Kotlin 0.25.0. No vendor adapter is required
for these synthetic tests.

Native session persistence is described in [harness state qualification](docs/harness-state-qualification.md),
with a reproducible Codex archive/restore probe and offline Claude SDK/ACP file
reader checks that share one file archive helper.

## Harness context

The Runner resolves the conversation's Agent and Prompt before sending a turn.
Kit uses its built-in instructions when no `kit` Agent exists; an Agent with a
missing Prompt fails preparation and leaves the unsent request queued for repair.
The Prompt's system and user guidance are supplied as configured instructions in
ACP text; ACP has no assumed system-message field.

An Agent can select default knowledge through `configuration.harnessContext`:

```json
{
  "harnessContext": {
    "metadataIds": ["00000000-0000-0000-0000-000000000001"],
    "collectionIds": [],
    "memoryIds": []
  }
}
```

Use the conversation context panel in Studio to add or remove content, collections,
WorkOps tasks/specifications/requirements and Git files. Conversation selections
take priority over managed defaults. The resolver deduplicates references and
considers at most 16 selected sources, followed by up to five automatically
selected memories. It checks current group
membership, the runtime's scope ceiling, entity access, and document content
access before fetching a body. Collections remain references for retrieval through
Bosca tools; their full contents are not preloaded. Unavailable sources have a
generic notice, without their titles or citations. Studio resolves its source
labels against the current viewer's permissions on each refresh.

Each deployment profile can set `contextCharacters` (default 32,000, range
4,096–131,072 UTF-16 code units). Instructions may consume at most half that
budget. Each document excerpt is capped at 4,000 characters. Reconstructed
conversation context uses at most 30 prior conversational rows and 8,000
characters, excludes current and future queued turns, and uses only remaining
space. The current request is supplied separately. These are deterministic
character bounds, not model token estimates.

Handoffs use embedded ACP text resources when the harness advertises that
capability, with text and stable Bosca source identifiers as the fallback. Retrieved
content is identified as reference data, separate from configured instructions.
The Runner persists a per-turn manifest with Agent/Prompt identities, instruction
and source revisions, source versions, selected integrations, character counts,
and omission reasons. Document bodies and credentials are not stored in that
manifest. Editable document bodies are hashed because their metadata version may
remain unchanged during edits.

Before a queued turn or archived native state is reused, the Runner checks the
context again. Changed instructions or source revisions start a new native session. The saved
workspace is retained when all previously injected sources remain accessible.
Deleted or revoked sources start a new session without restoring the old workspace
or conversation context: either can contain derived source text. Fresh sessions
receive current sources without replaying the prior conversation. The
old workspace stays in private archive storage until conversation deletion. The
conversation records this continuity boundary. Already submitted turns keep their
original manifest and are reconciled without replay.

The context manifest includes the selected Bosca and approved external MCP connections.
WorkOps sources include their current permitted description or linked document.
Git selections resolve their requested ref to an immutable commit before reading
the selected file. See the [working-set contract](runtime/README.md#selected-working-set)
for selection limits, source revisions and permission changes during restoration.

## Shared memories

The `bosca_memory` MCP tool saves a concise preference, fact or decision only when
the user requests it. Memories are ordinary ProseMirror content documents with
the `ai.memory` trait. PERSONAL memories use the account's private group;
COLLECTION memories inherit the selected collection's permissions. Reads also
require current access to any cited source and to the selected collection.
The memory service uses existing content storage and search.

The resolver automatically selects up to five relevant memories from personal memory, selected
collections and Agent `memoryIds` pins, within the context budget above. Each
reference includes its version and provenance. A changed source marks a memory
as uncertain; the harness is instructed to retrieve the current source. Studio's
context panel links each selected memory to the existing content editor.

The tool supports remember, get, search, update and forget. Reusing a request ID
with identical save input returns the existing memory. Updates and forget require
the latest returned revision, including edits made in Studio. Updates edit the current document through the ordinary content write path. Search returns permission-checked memories, with a default of
five and maximum of twenty, without exposing raw search hit counts.

Saved memories have independent retention: deleting the originating conversation
does not forget them. Optional expiry or forget excludes them from subsequent
memory reads. Existing content history and historical chat messages retain their
normal retention rules. Typed saves reject common credential formats and tool
instructions prohibit saving credentials.

The managed CLI records memories returned by its memory tool against the current
conversation, including reads outside the initial context selection. Between
turns and before archive restore, changes, expiry, deletion or lost access to a
previously supplied memory discard the native session and derived workspace.
The new session receives current context without replaying old conversation text.
This prevents a saved native checkpoint from reintroducing an obsolete memory.

Run the memory database and permission tests from the workspace root:

```bash
./gradlew :ai:ai:test --tests 'bosca.ai.memory.*'
./gradlew :content:core-content:test --tests 'bosca.content.security.MemoryPermissionEvaluatorTest'
```

The database test requires Docker and uses the established PostgreSQL and NATS
test containers. The runtime lifecycle test also checks memory changes across
idle archival and reactivation.
