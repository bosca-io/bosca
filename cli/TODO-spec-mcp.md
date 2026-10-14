# TODO — Spec / MCP follow-ups

Items left over from the workops Spec MCP overhaul. Each item is independent;
pick whichever unblocks your next workflow.

## Server-side gaps the MCP currently papers over

- [ ] **Sync direct document edits into linked Tasks across all editing surfaces.** MCP's `workops_plan` requirement writes and `workops_requirement action=set_content` now explicitly replace both the rich document and the linked Task's `summary`/`descriptionMarkdown`. Direct `content_metadata` and other editing surfaces still update only the document. A general document listener would need an agreed precedence rule for independently edited task descriptions; the current MCP operations use explicit replacement in a backend transaction.

- [x] **Expose spec transitions through MCP.** The existing server-side `WorkOpsSpec.transitions` field is included in the CLI schema snapshot and typed operations. `workops_spec action=transitions` lists reachable transitions, and `workops_plan action=context` returns them alongside requirement task transitions.

- [ ] **Add typed `SpecContextType` enum values** for `ANALYTICS_QUERY`, `SCRIPT`, and `GIT_FILE` (a specific file inside a git repo, distinct from the repo itself). Today the MCP instructions tell callers to fall back to `EXTERNAL_URI` for these. Update the workops enum, the `ContentEntityLinkExtractor` route patterns, and the MCP tool's enum list.

- [ ] **Re-export the GraphQL schema snapshot** at `cli/src/main/graphql/schema.graphqls`. The CLI's snapshot is stale — the live git server exposes 17 fields on `Git` (`repositories`, `repository`, `tree`, `blob`, `commits`, `branches`, `tags`, `compare`, `searchPaths`, `searchContent`, `scriptSourceRef`, `querySourceRef`, `sourceRefs`, etc.) but the snapshot only has 7. Until the snapshot is refreshed, the CLI cannot expose any of those.

## New MCP tools to add

These are GraphQL operations the server already supports but the MCP server does not yet wrap. Each unlocks a specific spec-planning workflow.

- [ ] **`bosca_git_repository`** — list repos by owner, get repo by id/owner+slug, list branches/tags/commits, browse tree, fetch blob, search paths/content. Lets Claude discover the right `gitRepositoryId` for a `GIT_RESOURCE` SpecContext, find file paths to bind to `gitPath`, and pull source for context during planning. (Schema needs to be re-exported first — see above.)

- [ ] **`bosca_analytics_query`** — list, get-by-id/key, execute (with parameters), edit/add/delete. GraphQL surface already exists at `AnalyticsQueries`. Lets Claude reference an analytics query from a spec, run it during planning to back up assertions in the doc, and link the result via SpecContext.

- [ ] **`bosca_script`** — list/get/run Bosca Scripts. Server-side Scripts currently only surface as `Git.scriptSourceRef(scriptId)` and `Git.sourceRefs(repositoryId)`; there's no general `Scripts.all` query. Decide: either add a server-side `Scripts` GraphQL type (list, get by key/id, execute) or document that Scripts are only discoverable via their git repository for now.

- [ ] **`bosca_ai_agent`** — list configured agents (`Agents.all`, `byKey`, `byId`), open a chat session against the Kit chat REST endpoint (`POST /api/kit/v1/chat`), surface the resulting `ChatSession` UUID for use as `agentSessionId`. Currently the MCP instructions tell Claude to launch agents from the Kit web UI and copy the session id back. Wrapping it would let Claude orchestrate Bosca agents directly during plan execution.

## Document body / TipTap coverage

Done in this round: task lists, blockquotes, code blocks (with `language`),
horizontal rules, plus the markdown→TipTap pipeline via CommonMark + GFM.
What's still incomplete:

- [ ] **`DocumentHtmlConverter` round-trip for tables.** CommonMark + GFM tables now produce `<table><thead><tr><th>…</th></tr></thead><tbody>…</tbody></table>` when going markdown→HTML, but `DocumentHtmlConverter.fromHtml` doesn't map `table` / `thead` / `tbody` / `tr` / `th` / `td` to the TipTap `table` / `tableRow` / `tableCell` node names. Tables coming from markdown will currently land as opaque blocks. Add the mappings (`th`/`td` both → `tableCell`; consider a `header: true` attr for `th`).

- [ ] **Strikethrough mark.** `markFromTag` recognizes `b`, `strong`, `em`, `i`, `code`, `a`. Add `s`, `del`, `strike` → `{type: "strike"}` (or whatever bosca's strike mark is named in `bosca.documents.marks`). CommonMark GFM emits `<del>` for `~~struck~~`.

- [ ] **Image with `metadataId`.** Images parsed from markdown become `<img src="…">` and round-trip as raw `<img>` elements with attrs. To use a Bosca-hosted image, the converter would need to detect a `bosca://metadata/<uuid>` URL (or similar convention) and emit a TipTap `image` node with `metadataId` set. Decide on the URL convention first.

- [ ] **Mention/container/superscript/bible custom nodes** are TipTap-specific and don't map to standard markdown. Out of scope for the markdown pipeline, but worth documenting in `workops_spec_instructions` that these must be authored via `jsonContent` (Path B) when needed.

- [ ] **`MentionNode` entityType extension.** Currently supports `metadata`, `collection`, `profile`. Extend to `script`, `analyticsQuery`, `gitRepository` so inline mentions can point at non-content entities. Touches `bosca.documents.MentionNode` plus the rendering side.

- [ ] **`MarkdownConverter.toMarkdown`** still uses the old line-based render path and silently drops content for many node types (code_block, task_list, table, image, mention, container). Mirror the new fromMarkdown approach in reverse: `Content` → `DocumentHtmlConverter.toHtml` → an HTML→markdown converter (e.g. flexmark or write a small adapter). Lower priority since `toMarkdown` is only used today by `RequirementService` to extract a Task description from a Requirement document.

## CLI ergonomics

- [ ] **`bosca workops spec create --name <title>`** — make `--metadata-id` optional in the human CLI command (`SpecCommands.kt`), mirroring the MCP path. Currently the command keeps `--metadata-id` required even though the server accepts it as optional. Trivial — just unwrap `.required()` and add `--name`.

- [ ] **Auto-fill `metadataVersion` on `workops_spec generate_tasks`.** The MCP currently asks the caller to pass `metadataVersion`. Instead, look up the spec's `metadataId` and use the metadata's current version automatically; allow override only when the caller explicitly wants an older snapshot.

## Tests

- [ ] **GFM coverage tests in `MarkdownConverterTest`.** The existing tests only verify headings and paragraphs. Add cases for: nested lists, ordered lists with `start`, links, bold/italic/code marks, blockquotes, fenced code blocks with language, task lists (checked + unchecked), tables (once `DocumentHtmlConverter` supports them), strikethrough.

- [ ] **`DocumentHtmlConverterTest` for new node types.** Tests for `taskList`/`taskItem`, `blockquote`, `code_block` (with and without language), `horizontal_rule` — both `toHtml` and `fromHtml` directions, plus round-trip.

## Workflow-state-machine hygiene

- [ ] **Default workflow assignment when `parentSpecId` is set.** `SpecService.create` resolves the default workflow via `resolveDefaultWorkflowId(project.id)`. Confirm this is correct when creating a child spec under a parent that uses a different workflow — child specs should likely inherit the parent's workflow rather than the project default. Add a test.
