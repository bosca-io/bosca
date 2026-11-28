# How Kit Works

Kit separates a durable Bosca conversation from a temporary native harness process. The conversation belongs to a principal. Its current runtime generation runs one harness with a workspace, private state files, and credentials issued for that generation.

A **conversation** is the durable thread. A **turn** is one accepted user request and its execution/result, which can include several tool calls. A **native session** is the harness's own conversation state. A **generation** is one managed workload lifetime; the same Bosca conversation can use many generations over time. A **profile** is the operator's approved runtime configuration.

![Kit architecture: clients use the Bosca API; the Runner drives a supervised native harness; PostgreSQL stores operational state and private object storage holds committed archives.](/diagrams/kit-runtime.svg)

[View the architecture diagram at full size](/diagrams/kit-runtime.svg).

## Who owns each responsibility?

| Piece | Responsibility |
| --- | --- |
| Studio or a protocol client | Send messages, display results, select permitted context/settings, and submit cancellation or permission decisions. |
| Bosca API | Authenticate callers, enforce API permissions, and persist conversation operations. |
| PostgreSQL | Store canonical history, ordered turns, approvals, ownership, policy/context snapshots, workload references, and archive inventory. |
| Job queue and Runner | Deliver background work, admit generations, resolve context, drive ACP, reconcile outcomes, and commit/collect archives. |
| Infrastructure controller | Materialize and remove Jobs or Services and generation credentials. Kubernetes and Swarm have separate controllers. |
| Native CLI supervisor | Authenticate and fence runtime requests, keep the native process warm, journal submitted turn identities/results, prepare tool connections, and archive/restore files. |
| ACP adapter and harness | Manage the native chat history and model context, call the model provider, select tools, and emit native events and permission requests. |
| Private object storage | Hold verified archives for cold return. It is shared storage accessed through Bosca, not a mounted shared workspace. |

Koog runs the outbound ACP strategy in the Runner. In this path it drives a remote harness turn; it is not a second model choosing tools beside the native harness.

## ACP and MCP serve different purposes

**ACP** is the conversation connection between the Runner and the harness. The Runner uses a Bosca WebSocket transport to the supervisor; the native adapter speaks its local protocol inside the container. ACP negotiates capabilities, creates or restores a session, sends a prompt, streams updates, and requests cancellation.

**MCP** supplies tools to the harness. The standard Bosca connection launches the CLI's embedded MCP server inside the runtime and calls the Bosca API using that generation's scoped credential. Approved external connections can use supported HTTP or standard-input/output transports.

Bosca also supplies a bounded initial context package as ACP content: configured instructions, permitted source excerpts or references, selected memories, and, when rebuilding a session, bounded prior conversation context. Context is therefore **not exclusively retrieved through MCP**. Tools let the harness retrieve additional authorized information and act on Bosca after setup.

Source selection does not preload an entire collection or clone a Git repository. Git file context resolves a selected ref to a commit and reads that file. Full repository checkout and native filesystem work are separate operations in the harness workspace.

The native harness owns its token window, compaction, and internal history management. Bosca's initial-context character budget is not a model token budget or a promise of complete recall. Bosca records a per-turn context manifest with source identities, revisions, character counts, and omission reasons; that manifest excludes source bodies and credentials. The native session and its archive can still contain the content actually supplied to the harness.

## Four kinds of retained information

| Information | Where it lives | What it means |
| --- | --- | --- |
| Conversation and turn history | Bosca/PostgreSQL | The canonical user-facing record and durable execution outcomes. It is not the native harness's complete internal state. |
| Native state and workspace | Live workload; then a private archive | Opaque files used for native continuity and working files. They belong to a specific harness/state policy. |
| Saved memories | Bosca content documents with the `ai.memory` trait | Explicitly saved preferences, facts, or decisions, with ordinary permissions and provenance. They can be supplied to another permitted conversation. |
| Saved artifacts | Their owning Bosca domains | Content documents, generated images, scripts, pipelines, queries, visualizations, and other actual records returned by tools. Their lifecycle is separate from the temporary workspace. |

A file written only in `/workspace` is not automatically a Bosca content item, Git commit, or published artifact. A memory is not an export of the native conversation. Returning to a saved conversation and asking another harness to use a saved memory are different workflows.

## Warm, archived, and restored

A **generation** identifies one admitted workload lifetime. Several turns can reuse its native process. Reconnecting a Runner does not create a new native session when the existing setup is still valid.

Idle archival follows this order:

1. Finish or reconcile accepted work and enter retirement.
2. Stop the native process and remaining workspace writers in the private PID namespace.
3. Package the harness's operational directory, workspace, and persistent scratch files with a manifest and hashes.
4. Upload through Bosca's object storage service and read back the object to verify size and checksum.
5. Commit the archive pointer under the current owner, then revoke the generation credential and remove the workload.

Restore uses fixed paths, verifies the manifest and hashes, and supplies fresh credentials. `CODEX_HOME` and `CLAUDE_CONFIG_DIR` identify the native operational directory; Bosca restores that directory as opaque files, including settings, plugins, databases, journals, and newly introduced native files. It does not select a list of database filenames for each native version. Known provider-login files and transient IPC sockets are excluded. Managed tool connection settings and runtime receipts live outside that directory and are regenerated.

A runtime image/version upgrade within the same harness family preserves the native session and directory. The new harness opens its own state and performs any native migrations it supports, as it would after a local upgrade. A failure to open that state remains visible; Bosca does not promise that every vendor upgrade or downgrade is compatible. Profile, authority, integration, or context changes that require a different session still follow the reconstruction rules below.

This is a file checkpoint after writers stop, not a running-process snapshot or a shared filesystem. An archive does not preserve an operating-system process, active network connection, or an arbitrary background operation. Unsupported file entries or detected credential values can make archival fail visibly.

## Why a return can start a new native session

Before queued work or an archive is reused, Bosca resolves current access and source revisions. Changed instructions or source content can invalidate the native context. Authorized source changes can retain workspace files while starting a new native session. Deleted or inaccessible sources prevent restoration of derived native state and workspace; those files may contain the old source text.

Memories returned through the managed memory tool are tracked too. A relevant memory update, expiry, deletion, or loss of access can invalidate the saved native state and derived workspace. The next session gets current permitted context rather than old conversation text being replayed.

Switching profiles does not transplant a Codex checkpoint into Claude or vice versa. The transition uses permitted canonical context and eligible workspace files, with a visible continuity notice. See [Configuration](/kit-reference/configuration) and [Working with Kit](/kit-reference/using-kit).

## Credentials and authority

| Credential | Used for |
| --- | --- |
| User's Bosca login or API token | Access to the conversation and API operations. External scoped chat clients need `ai:execute`. |
| Generation's scoped `BOSCA_TOKEN` | Calls from Bosca tools inside that runtime. Its grant intersects the deployment's scopes with the initiating caller's scopes and effective groups. Entity permissions still apply. |
| `BOSCA_RUNTIME_TOKEN` | Generation-specific supervisor control and ACP access. It is not a general Bosca API token. |
| Provider and external MCP credentials | Their respective external accounts. Operators mount these independently of the Bosca grant. |
| Swarm controller bearer token | Private access from Bosca to the manager-side runtime controller. |

The generation-scoped Bosca credential does not use a refresh-token exchange. It expires and is revoked when the generation retires. A new generation receives a new credential. Runtime profiles cannot grant general `ai:execute` or `mcp:execute`, so tool calls cannot recursively start arbitrary Kit chats. Managed delegation has its own restricted path.

Approving a native permission request does not add Bosca permissions. Conversely, the presence of native approval controls does not mean every authorized Bosca write requires a separate human approval. Domain API checks and native tool approval are distinct boundaries.

## Recovery preserves uncertainty

Runtime controls carry the generation, ownership epoch, and supervisor instance identity. A new process cannot silently inherit an old process's identity. A replacement is fenced; uncertain sent work is interrupted rather than rebound to a new process.

Before forwarding a prompt, the supervisor journals its durable turn identity. Recovery reads its receipt. Duplicate delivery of that identity is rejected; an unknown outcome is not treated as permission to resend. This does not make external tool side effects exactly-once transactions.

The last committed archive and unsent messages remain available after a failed generation. **Retry session** can resume queued work in a repaired generation. It cannot recover unarchived local changes or undo a completed external action. Read the durable turn result and inspect affected records before deciding whether to request an operation again.
