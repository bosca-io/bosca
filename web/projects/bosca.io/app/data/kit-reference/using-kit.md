# Working with Kit

Use Kit through Studio's AI chat or through the CLI's MCP, ACP, and A2A adapters. They reach durable Bosca conversations; closing a tab or disconnecting a protocol client does not by itself cancel accepted work.

## Start with a clear outcome and a working set

Open a Kit conversation in Studio. Describe the result you want, identify the records involved, and distinguish a draft from a publishing or execution request. For example:

> Use the selected specification to draft a setup guide as a new content document. Link the saved draft and identify any missing information.

The context panel can select content, collections, WorkOps tasks/specifications/requirements, and individual Git files. Changes apply to the next message. Selection and status reads do not start a workload or extend its idle timeout.

Conversation selections take priority over Agent defaults and are deduplicated. Bosca considers at most 16 source candidates, then up to five selected memories, within the profile's character budget. A collection is a reference for later retrieval, not every document in the collection. A selected task contributes its own description; linked rich documents require their own content permissions. Git selection does not clone the repository.

Inspect omissions and truncation notices. An unavailable source has a generic notice rather than a disclosed name or body. Selecting a record does not grant access to it.

## Read results as records, not just text

| Work | What the integration provides | What to verify |
| --- | --- | --- |
| Documents | Read, create, and edit structured content; server-side Markdown conversion is available. | Open the saved document. Saving, readiness, and publication are separate operations. |
| Images | Generate or edit through Bosca's configured image service and save a new content item. | Check the returned image/content item and destination. The source image is preserved by the edit operation. |
| Analytics | Discover warehouse sources, run read-only investigations, and save supported queries, charts, visualizations, or dashboards. | Inspect the rows, truncation, freshness, and available SQL/parameter evidence. Cached results retain their original execution context. |
| Scripts | Discover, read, validate, create/edit, enable/disable, and execute through the scripting API. | Validation of an unsaved script compiles without executing it. Saving and execution need their respective scopes and permissions. |
| Pipelines | Inspect, validate, and save graphs, then use the existing durable execution path. | Check validation and the saved graph/run. Administrative authoring checks and run/action permissions still apply. |
| Platform data | Discover the GraphQL schema and execute authorized queries/mutations. | Production introspection requires administrator membership. A generic tool does not bypass domain access checks. |
| WorkOps and source material | Work with permitted records and selected source context. | Check the actual updated record or immutable Git file revision. |

Studio can display typed tool resources, inline images, plans, file diffs, saved-content links, and structured query results. Supported resource links open the corresponding Bosca editor. Rendering a result does not fetch arbitrary resource URLs or imply that an operation published anything.

These are available tool paths, not a guarantee that a model will choose the right tool or finish every request. An installation needs the corresponding modules, integrations, scopes, and permissions. Native shell tools operate inside the workload; their filesystem results remain workspace files until deliberately saved elsewhere.

## Model and harness choices

An administrator sets managed defaults and approved profiles. Users with the private policy's `EXECUTE` permission can select permitted overrides. Studio exposes model and reasoning choices only where the policy and current harness capabilities allow them.

Changing a model can keep the warm native session. A profile or model-policy change drains the old generation and starts a new native session with eligible workspace files. A saved choice is rechecked before each turn and after restore; stale or revoked choices fall back to managed defaults visibly. Selecting a new profile applies to subsequent activation/work, and its model choices become available after activation.

The displayed model list does not establish provider entitlement, remaining account budget, or a supported service tier. Provider account limits still apply.

## Session controls

| Control/state | Meaning |
| --- | --- |
| **Stop** | Request cancellation of active and already accepted queued work. Wait for the recorded terminal outcome. Messages accepted afterward remain queued. |
| Native permission options | Answer the specific request offered by the harness. Decisions are bound to the turn, generation, operation, principal, and policy revision. |
| **Archive session** | Retire an idle, ready runtime through verified archival. |
| **Resume session** | Activate an archived conversation without sending another message. |
| **Retry session** | Activate a repaired failed conversation and its queued work. It can cause pending work to proceed. |
| Delete conversation | Purge its runtime and checkpoint inventory through retried cleanup. It does not undo external actions or forget independently saved memories. |

A cancellation acknowledgment is not a terminal result. A turn can complete before cancellation takes effect. If the harness cannot confirm cancellation within its grace period, Bosca removes the runtime and records an interrupted outcome.

## Save memories deliberately

Ask Kit to remember a concise preference, fact, or decision when you want it available later. The `bosca_memory` tool supports remember, get, search, update, and forget. Personal memories use the account's private group; collection memories inherit the selected collection's access. Cited sources are checked on reads.

Memories are editable content documents, not native chat checkpoints. They retain provenance, can expire, and can become uncertain when a source changes. They are subject to content history and retention. Forgetting a memory excludes it from later memory reads; it is not a promise that earlier chat messages or content history are erased.

The managed runtime tracks memories supplied to a conversation, including later tool reads. A changed or inaccessible memory can require a fresh native session and workspace. Another harness can receive the same currently permitted saved memory; this does not transfer the original harness's hidden state. Real cross-provider behavior still needs qualification with the deployed accounts.

## Managed child tasks

Delegation is opt-in. With an approved policy and `ai:delegate`, a parent can use `bosca_delegate` to start a child with a bounded brief, expected result, selected context, profile, and deadline.

Each child has its own native session and writable workspace. It shares the principal and a narrowed authority ceiling, not the parent's full conversation or checkout. Its MCP connections cannot exceed the parent's selected connections. Children can return a typed summary and Bosca metadata artifact references. A proposed result alone is not completion; the native terminal receipt determines status.

Depth, active children, total children, and reserved runtime seconds are bounded across the tree for the initiating turn. These are runtime budgets, not a provider token or spending cap. Parent cancellation reaches active descendants. Partial output remains marked partial on failed or interrupted tasks.

## Use a protocol client

Configure a saved Bosca CLI profile as described in [CLI](/developers/cli). Then choose an inbound adapter:

```sh
bosca --profile work mcp-server
bosca --profile work acp-server
BOSCA_PROFILE=work bosca a2a-server
```

- MCP exposes `kit_chat` and `kit_download`. Reuse the returned `sessionId` for a follow-up. `kit_chat action=get` reads history; explicit cancellation requests use `action=cancel`.
- ACP exposes Kit as an agent over standard input/output. Its session ID is the durable Bosca chat UUID. This inbound adapter is distinct from the Runner's outbound connection to a native harness.
- A2A listens on loopback by default, with Kit at `http://127.0.0.1:8091/a2a/kit`. Publishing it beyond loopback requires its separate inbound bearer token and an explicit public URL where applicable.

External scoped clients need `ai:execute` for chat reads, writes, and subscriptions. Harness credentials deliberately do not receive that scope. Protocol cancellation and timeouts have different meanings: consult the durable conversation after an uncertain response instead of submitting the same side-effect request again.
