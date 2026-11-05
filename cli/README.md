# bosca-cli

Unified command-line tool for the Bosca platform, handling version coordination, Kubernetes deployment (via cdk8s), content administration (via GraphQL), artifact management, MCP and ACP servers,. Compiles to a GraalVM native binary named `bosca`.

## Install

```bash
curl -fsSL https://bosca.io/cli/install.sh | sh
```

This downloads and installs the latest released `bosca` for your platform from
the project's GitHub Releases (no login required), or from a configured Bosca
Artifacts repository. The script
([`install.sh`](install.sh)) detects your OS/arch, resolves the newest stable
release, verifies the package against the release's
`SHA256SUMS`, and installs it (a signed/notarized `.pkg` on macOS → `/usr/local/bin`; a tarball on Linux).

Useful overrides (environment variables):

| Variable | Purpose |
|---|---|
| `BOSCA_VERSION` | Install a specific version instead of the latest |
| `BOSCA_INSTALL_DIR` | Target dir for tarball installs (default `/usr/local/bin`) |
| `BOSCA_CLI_REPOSITORY` | Read releases from another `owner/name` repository (default `bosca-io/bosca`) |
| `BOSCA_CLI_ARTIFACTS_URL` | Download from a Bosca raw repository instead of GitHub (e.g. `https://artifacts.example.com/raw/bosca/bosca-cli`) |
| `BOSCA_CLI_ARTIFACTS_TOKEN` | Optional Bosca API token for private artifact downloads |
| `GITHUB_TOKEN` | Optional token when anonymous GitHub API requests are rate limited |

For packages published by the Bosca CLI release pipeline, set the raw repository
download URL on the `sh` side of the pipe:

```bash
curl -fsSL https://bosca.io/cli/install.sh | \
  BOSCA_CLI_ARTIFACTS_URL=https://artifacts.example.com/raw/bosca/bosca-cli sh
```

This queries `/raw/bosca/api/bosca-cli` for the newest stable version, then
downloads its platform package and `SHA256SUMS` from `/raw/bosca/bosca-cli/<version>/`.
Set `BOSCA_VERSION` to download an exact version without querying the listing.

To make the website's install command use Bosca Artifacts by default, configure
the website with
`NUXT_CLI_INSTALL_SCRIPT_URL=https://artifacts.example.com/raw/bosca/bosca-cli/<version>/install.sh`
and redeploy it. The website serves that script with its raw package repository
configured automatically. Publish the updated `cli/install.sh` at that URL first.

### Version & updates

```bash
bosca --version            # print the installed version and exit
bosca version              # same, as a subcommand
bosca version --check      # check public GitHub releases for a newer version
```

Rerun the install command to upgrade, or use the command printed by the update
check. `version --check` always makes a fresh request and exits with a nonzero
status if the release source cannot be checked.

After successful interactive commands, the CLI checks for updates at most once
per day and prints an "update available" notice to standard error when a newer
release is available. Automatic checks have a short network timeout; unavailable
release sources do not fail your command. Disable automatic checks with
`BOSCA_NO_UPDATE_CHECK=1` (or `BOSCA_UPDATE_CHECK=0`). Explicit `version --check`
requests still run. Release comparisons ignore build metadata and order numeric
version components and prerelease identifiers numerically.

Release checks query GitHub's API for `bosca-io/bosca` directly. `BOSCA_CLI_ARTIFACTS_URL`
(or `bosca version --check --artifacts-url <raw-repository-url>`) checks an
explicit Bosca repository directly. `BOSCA_CLI_REPOSITORY` selects an explicit
GitHub repository. Cached notices are scoped to the selected release source.

## Accounts and servers

The CLI stores each Bosca server/account pair as a named profile. Logging into a
profile creates or updates it and makes it active:

```bash
bosca login --profile work \
  --url https://work.example.com/graphql \
  --username me@example.com \
  --password '...'

bosca login --profile personal \
  --url https://personal.example.com/graphql \
  --api-token '...'
```

Manage saved profiles without displaying their tokens:

```bash
bosca profile list
bosca profile show work
bosca profile use personal
bosca logout --profile work       # clear only work's credentials
bosca profile remove work         # remove the local profile
```

`profile use` changes the persistent default. To use another saved account for
one invocation without changing that default, put `--profile` before the
subcommand or set `BOSCA_PROFILE`:

```bash
bosca --profile work workops task list --project WORK
BOSCA_PROFILE=work bosca mcp-server
```

Existing per-command `--url`, `--token`, `--username`, and `--password` options
still take precedence when supported. Stored credentials are only sent to their
profile's server; when `--url` targets a different server, also provide
`--token` or `--username`/`--password`. Changing a saved profile's endpoint with
`bosca config --url ...` clears credentials associated with its previous
server. On first use after an upgrade, the former single-account
`endpoint`/`auth` configuration is migrated automatically to a profile named
`default`.

## Analytics

Saved analytics queries, visualization definitions, and dashboards are
available from one command tree:

```bash
bosca analytics query list
bosca analytics query get --key weekly-traffic
bosca analytics query execute --key weekly-traffic \
  --param start='"2026-07-01"' --param end='"2026-07-31"' --format json
bosca analytics query refresh --key weekly-traffic

bosca analytics visualization list
bosca analytics visualization render --key weekly-traffic-bars \
  --param start='"2026-07-01"'

bosca analytics dashboard list
bosca analytics dashboard render --key operations-overview \
  --param region='"us-central"'
```

Create and update commands accept JSON configuration directly or through
`@file`. Query and dashboard parameter definitions use repeatable
`--parameter-json` values:

```bash
bosca analytics query create \
  --key weekly-traffic \
  --name "Weekly traffic" \
  --description "Sessions grouped by week" \
  --query-file ./weekly-traffic.sql \
  --parameter-json '{"parameter":"start","name":"Start","type":"DATE","required":true}'

bosca analytics visualization create \
  --key weekly-traffic-bars \
  --name "Weekly traffic" \
  --description "Sessions per week" \
  --query-id 00000000-0000-0000-0000-000000000000 \
  --type BAR \
  --configuration-json '{"x":"week","y":["sessions"]}'
```

Terminal `render` commands and `bosca analytics sample` use Tamboui widgets
rendered into an in-memory terminal buffer. The resulting deterministic text
works the same way in interactive shells, redirected output, and agent sessions.

Saved-query execution includes cache freshness and, with query VIEW access, the
prepared SQL, actual bound parameters, execution time and duration. Those values
come from the execution that produced the rows, including on cache hits. Relative
dates are resolved once for that execution. Execute-only callers retain access to
rows without receiving the query definition or its default parameter values.

### Warehouse investigations through MCP

`analytics_sql` discovers `catalogs`, `schemas`, `tables`, and table columns with
`describe`, then runs a single read-only query with `execute`. Discover actual names
before composing SQL. Lists use offset pagination; list and result limits default
to 100 and accept 1–1000. SQL execution returns exact executed SQL, column types,
records, execution time, duration, and whether rows were truncated. These uncached
results are evidence for that execution; their MCP resource URIs are not saved queries.

The caller must be an administrator or analytics manager. Scoped credentials also
need `analytics:sql`; permission to execute a saved query does not grant warehouse
access. The server uses its configured `trino-readonly` connection and Trino's parser
to reject writes, multiple statements, inline functions, and session overrides.
The database credential must have read-only access to the catalogs it exposes.

For an inline chart, `execute` accepts `visualizationType` (`NUMBER`, `BAR`, `LINE`,
`PIE`, or `TABLE`) and column mappings in `configuration` (`x`, `y`, `label`, `value`,
`columns`). Studio renders those returned rows with the SQL and timing. Use the
existing `analytics_query`, `analytics_visualization`, and `analytics_dashboard`
tools to save artifacts when requested. Those actions retain their own permissions.
User/session/engagement questions should use the standard saved metric queries in
the MCP server instructions; ad-hoc queries must preserve those definitions.

### Documents through MCP

`content_metadata action=create` accepts `name` and inline `documentContent` in the
Bosca Content wrapper: `{ "document": { "type": "doc", "content": [...] } }`.
The title defaults to `name`, and document content defaults the metadata type to
`bosca/v-document`. An explicit content type is preserved. Supply
`parentCollectionId`, `languageTag`, `templateId`, and `templateVersion` when needed.
The existing add mutation saves the metadata and typed document together; creation
does not mark it ready or publish it. A title without a body creates an empty document.

Use `get_document` to read the saved body and template. `set_content` accepts exactly
one body format; use `documentContent` for a structured edit or `markdownContent`
for server-side conversion into the same document model. Body edits preserve the
template binding and use the existing collaboration reset behavior. Raw text,
opaque JSON, and file uploads use `set_content` on the saved non-document item.

Successful document operations return the saved content link. If a visibility
setting fails after creation, the error retains that ID and link; read the saved
item before retrying its settings. Do not repeat an uncertain creation request.

### Pipelines through MCP

`bosca_pipeline` exposes reusable Bosca pipelines through `list`, `get`, `catalog`,
`validate`, `save`, `dry_run`, `run`, and `run_status`. Discovery supports search and
bounded offset pagination. The catalog supplies the registered node keys, input and
output slots, settings, shapes and value types used by Studio.

Read before editing and pass `expectedVersion`. `pipeline` is a definition patch:
omitted fields retain their values, and null clears optional fields. Save validates
through the engine and assigns deterministic editor positions. `preview=true`
returns that definition without saving it. New pipelines must be inactive.
Activating triggers, API/public access or a schedule on an existing pipeline
requires explicit user authorization and `confirmedActivation=true`.

Use `dry_run` with a sample JSON `payload` to inspect outputs and proposed actions.
Real `run` requires authorization for that exact pipeline and payload plus
`confirmed=true`. These flags record the caller's assertion; they are not an
independent approval policy. Runs execute as durable jobs under the caller's
captured authority. Poll the returned `runId` with `run_status`; RUNNING and
SUSPENDED are unfinished. Polling includes final output, errors, waits and the
node timeline. Do not automatically retry an uncertain execution.

Administrator inspection and authoring remain administrator-only. Scoped clients
must grant `pipelines:view` for inspection/validation and `pipelines:edit` for saves.
Private execution requires `pipelines:execute` and the existing EXECUTE grant or
administrator/service-account authority; action nodes also require their own
domain permissions. Public pipelines retain their existing open execution rule.
Browser sessions keep their ordinary group checks. Broad security scopes are not
required for the harness workflow. Pipeline definitions and runs return MCP
resource links that open their existing Studio views.

### Scripts through MCP

`bosca_script` discovers and reads scripts, validates unsaved Kotlin source,
creates and edits definitions, enables or disables scripts, deletes them, and
executes them. Use `dryRun=true` for a create/edit preview. `compile=true` on
validation or a preview also checks syntax and types without executing the script.
The worker performs compilation when the API server excludes the Kotlin compiler.

Use `get` before `edit` and pass its `version` as `expectedVersion`. The `script`
object contains definition fields; omitted edit fields retain their values, while
explicit null clears optional JSON fields. Pass execution input as a JSON value.
Execution can have side effects; do not automatically retry an uncertain result.

Discovery and source reads require `scripts:view`, execution requires
`scripts:execute`, and management requires `scripts:manage`, in addition to the
corresponding group permissions. Creation and promotion to TOOL require an
administrator or service-account group. TOOL creation registers an agent tool and
assigns it to `kit.script` when that Agent exists. EPHEMERAL deletion retains a
soft-deleted record; other script types use the existing permanent deletion.

### Kit through MCP

`bosca mcp-server` exposes Kit as a conversational tool:

- `kit_chat` — ask Kit natural-language questions, start or continue sessions,
  inspect history, request cancellation, and delete sessions. Kit can answer analytics questions and
  return structured visualization data. Generated images are returned as MCP
  image content; pass `downloadDirectory` to save them immediately.
- `kit_download` — download a Kit-generated asset by its returned `metadataId`
  to an explicit local path. Existing files are not replaced unless
  `overwrite=true`.

Reuse the `sessionId` returned by `kit_chat action=ask` for follow-up questions
that need the same conversational context.

Harness turns return typed tool resources, images, audio and file diffs as well
as the conversational answer. `harnessTurns` contains turn and tool provenance;
content blocks carry the corresponding IDs. `includeImages=false` omits inline
image blocks. An explicit `downloadDirectory` also saves inline harness images
using the turn UUID and content index, without overwriting existing files.

An MCP `notifications/cancelled` notification for an active `kit_chat ask` forwards
cancellation to Bosca after the prompt has been accepted. The cancelled MCP call
has no response; use `kit_chat action=get` to inspect its durable outcome.
`kit_chat action=cancel` with a `sessionId` explicitly requests cancellation of
the conversation's active and queued work. Its acknowledgment confirms receipt
of the request; a terminal history event confirms the outcome.

### Kit through ACP

`bosca acp-server` exposes Kit as an ACP v1 agent over standard input and
output. ACP clients can create a conversation or load an existing one; the ACP
session ID is the durable Kit chat session UUID. Kit responses are returned as
text, JSON resources for structured analytics results, and image content for
generated assets. Completed harness plans and tool calls retain their turn-scoped
IDs, resources and diffs. Cancelled and interrupted outcomes remain distinct
from successful completion. `session/cancel` requests cancellation in Bosca and
drains the harness response before returning the prompt's terminal result. A turn
that finishes before cancellation takes effect retains its completed result.

For example, add Kit as a custom agent in Zed's `settings.json`:

```json
{
  "agent_servers": {
    "kit": {
      "type": "custom",
      "command": "bosca",
      "args": ["--profile", "work", "acp-server"],
      "env": {}
    }
  }
}
```

The command's standard output is reserved for ACP traffic. Select a saved Bosca
profile with `--profile` as shown above, or set `BOSCA_PROFILE` in the agent's
environment.

### Kit through A2A

Other agents can talk to Kit through the standard A2A JSON-RPC protocol:

```bash
BOSCA_PROFILE=work bosca a2a-server
```

By default the CLI listens on `127.0.0.1:8091`, serves Kit at
`http://127.0.0.1:8091/a2a/kit`, and publishes the agent card at
`http://127.0.0.1:8091/.well-known/agent-card.json`. A2A conversation contexts
are mapped to durable Kit chat sessions, so follow-up messages retain context.
Analytics results are returned as structured data artifacts and generated images
as base64-backed file artifacts that an A2A client can save directly.
Harness results use one artifact per durable turn, with correlated data and file
parts for resources, diffs, images and audio. These adapters forward saved content;
they do not fetch arbitrary resource URIs.

`tasks/cancel` forwards cancellation for the task's conversation. It reports
`canceled` only after Bosca confirms that outcome. If confirmation takes longer
than five seconds, the cancellation request remains pending and the RPC reports
that it could not yet confirm cancellation; poll `tasks/get` for the eventual
state. Completed and interrupted turns retain their actual terminal state.
Cancellation before submission prevents the prompt from being sent. A local
timeout or a disconnected protocol client does not by itself cancel durable work.

Binding beyond loopback requires a separate inbound bearer token and an explicit
public URL when using a wildcard address:

```bash
BOSCA_A2A_ACCESS_TOKEN='...' bosca a2a-server \
  --host 0.0.0.0 \
  --public-url https://agents.example.com/a2a/kit
```

The A2A access token protects the local protocol endpoint; the selected Bosca
profile independently supplies Kit's downstream Bosca credentials.

### Analytics through MCP

For direct access without Kit's conversational reasoning, `bosca mcp-server`
also exposes three compact analytics tools intended for Codex and Claude:

- `analytics_query` — list/get/create/update/delete/refresh/execute queries and
  manage query permissions.
- `analytics_visualization` — manage visualization definitions and permissions.
- `analytics_dashboard` — manage dashboards, visualization placement, and
  permissions.

The tools accept stable keys where possible, preserve omitted fields during
updates, and bound query execution results with `returnedRecords` and
`truncated` metadata. Execution also returns those rows as an embedded JSON
resource, including cache information. Saved query, visualization, and dashboard
get/create/update results include Bosca resource links. Content item operations
similarly return resource links to their saved metadata. The JSON text remains
available to MCP clients; resource enrichment does not require extra API reads.
Configure the MCP server using the same saved profile:

```bash
BOSCA_PROFILE=work bosca mcp-server
```

Managed harness containers run `bosca harness serve`. The CLI owns the warm ACP
process, runtime protocol, archive/restore, and MCP connection preparation.
`bosca harness mcp <key>` starts a prepared MCP connection from the generation's
private settings. See [runtime deployment and checks](../ai/kit/runtime/README.md).

For a managed runtime, supply its scoped API token through `BOSCA_TOKEN` and
its endpoint through `BOSCA_ENDPOINT`, then run the same command:

```bash
bosca mcp-server
```

The existing tools use that token directly. Bosca enforces its scopes and the
caller's permissions; API tokens do not use the login or refresh flow. The runtime
owner issues the credential and revokes it on retirement.

### GraphQL through MCP

`bosca_graphql` exposes live schema discovery and query/mutation execution through
the same authenticated transport as the domain tools. Use the domain tool when
it covers the task. For other operations, `roots` lists root fields, `search`
finds type/field names and descriptions with offset pagination, and `type`
returns the selected type's fields, arguments, wrappers, input defaults, enum
values and interfaces. Discovery follows the server's existing introspection
policy: it must be enabled, and production callers need administrator membership.
A denied discovery request reports the error without substituting a local schema.

`execute` accepts the GraphQL document, an optional JSON object of `variables`,
and an `operationName` when selecting from multiple named operations. It supports
queries and mutations; subscriptions require a streaming client. API scope,
group and entity checks still apply. Mutations and side-effecting queries require
the user's authorization. This tool adds no independent approval policy, and an
uncertain operation must be reconciled rather than automatically repeated.

Results include structured data and error messages, including partial data, plus
an embedded JSON resource. Studio displays that resource in a read-only JSON
viewer. Rendering does not retrieve the URI or issue another operation. An MCP
cancellation interrupts the matching GraphQL request, including stalled response
headers or bodies, and leaves the MCP reader available for subsequent requests.
Cancellation does not undo an operation the server already performed.

The CLI sends each GraphQL POST body once per HTTP call. Lost responses,
retryable HTTP responses and redirects that require resending the POST remain
visible to the caller; inspect an
uncertain write before taking another action. Configure the final canonical
GraphQL URL in `BOSCA_ENDPOINT`, or use that URL at login for a saved profile, and
restart the MCP connection after changing it. The existing managed-login 401
refresh path can start a fresh request after rejection. Direct API tokens fail
401 without refreshing or substituting saved credentials.

### Finding collections through MCP

`content_collection action=find` matches collection attributes through the
existing content API. For example, discover topic candidates with
`filters: [{"key":"type","value":"Topic"}]`. All filters must match. `offset`
defaults to zero and `limit` to 20, with a maximum of 100. Results include each
visible collection's ID, name and public flag. Private results require the
caller's collection scope and current permissions; public reads retain the
API's ordinary rules. The lookup uses a typed operation and works with
introspection disabled. Use the returned collection IDs when asking Kit to
suggest or apply topics.

### Scripture through MCP

`content_scripture` reads installed translations through the same scoped GraphQL
transport. `list` returns accessible editions, with offset pagination (20 metadata
items by default, at most 100). Pass the returned `metadataId` as `id`, plus its
`version` and `variant`, to select an exact edition. `books` lists books and
chapters; `references` resolves a human reference without retrieving passage text.

`verses` retrieves the selected passage component trees and source attribution
through `Bible.aiContext`. Bosca checks content access and the source's
`AI_CONTEXT` grant. Denial returns an error; the tool does not fall back to a
display read or another translation. Preserve the returned copyright and citation
when using the text. An empty result is not a retrieved passage.

### Stored images through MCP

`content_image` supports `generate` (`prompt`), `edit` (`sourceId`, `prompt`) and
`get` (`id`), plus `attach` (`id` and a target). Generation and editing accept an optional `name` and
`parentCollectionId`. Both save a new image through Bosca's configured provider;
editing preserves the source. They leave the result uploaded and unready.

The token needs `content:edit` and the caller needs destination collection EDIT
access, including `collections:edit` for scoped credentials. Root creation requires
editor membership. Edits and previews also need source/result content VIEW access.
A successful result contains the new content ID, a Studio content link and an image
preview when readable. If the preview fails, the saved ID remains in the result;
retry `get` instead of generating again. Do not retry an uncertain generate/edit
mutation automatically. Readiness and publication remain separate content operations.

For `attach`, supply exactly one `targetMetadataId` or `targetCollectionId` and
optionally `targetLanguageTag` for a collection variant. The caller needs source
content VIEW and target EDIT access. Omit `attributeKey` to select the sole image
attribute or receive template choices without writing. Single-image attributes
replace the current relationship; list attributes keep other images. The template
supplies target size and default crop ratio; an explicit `crop` uses source pixels
(`top`, `left`, `width`, `height`). A successful attachment returns a target link.
Warnings describe any optimization or collaboration work that failed after saving.

### Shared memories through MCP

`bosca_memory` provides `remember`, `get`, `search`, `update` and `forget` actions
for explicit user-requested preferences, facts and decisions across conversations.
PERSONAL is the default scope and uses the account's private group. COLLECTION
requires `collectionId` and follows the collection's permissions; cited source
content further restricts reads. Supply `sourceMetadataId` for content-derived
facts, and `expires` for an optional retention deadline.

For `remember`, supply a UUID `requestId` and reuse it with identical input on
retry. Read the memory before `update` or `forget` and pass its `expectedRevision`.
Search accepts text, collection IDs, pinned IDs and tags; it returns five memories
by default, at most twenty. `sourceChanged` marks a remembered fact as uncertain.
The returned provenance identifies the source to consult before correcting it.

The caller needs content view/edit/delete scopes for the corresponding actions,
collection view/edit scopes for shared memories, and search access for text
search. Entity permissions still apply. A managed runtime sets
`BOSCA_MEMORY_SESSION_ID` so reads are recorded against its conversation and
invalidate stale native context. Saved memories persist independently of the
conversation until forgotten or expired; content and chat history follow their
existing retention rules. Memory references open in Studio's content editor.
Expiry and source-availability checks apply to memory retrieval. Access to the
stored document through content APIs follows ordinary metadata permissions,
including administrator access.

## Swarm backends

`bosca swarm runtime-controller --config <profiles.json>` manages Kit runtime
Services on a Swarm manager. See [Kit on Swarm](../ai/kit/runtime/SWARM.md) for
controller settings, native profiles, Secret delivery and lifecycle validation.

The shared `dragonfly` service runs Dragonfly `v2.0.2`, configured through
`images.dragonfly`. Existing configs gain this image setting automatically.
Dragonfly uses fresh storage, so disposable cache and lock data need no import;
see the [Dragonfly cutover steps](../deployments/bosca-swarm/README.md#dragonfly).

Set each site's `cacheBackend`, `pubsubBackend`, `jobQueueBackend`, `distributedLockBackend`, and `counterBackend` independently to `nats` (the default) or `redis` in the Swarm JSON configuration. For Redis caching with NATS pub/sub, set `"cacheBackend": "redis"` and `"pubsubBackend": "nats"`. See [backend selection](../deployments/bosca-swarm/README.md#backend-selection) for deployment and switching steps.

## Swarm OAuth redirects

Set a site's `oauthRedirects` list in the Swarm JSON configuration to control allowed destinations after sign-in, then render and deploy the configuration. Omitting the setting keeps the existing Studio and local development defaults. See [OAuth redirects](../deployments/bosca-swarm/README.md#oauth-redirects) for the configuration format and image requirements.

## Deployment image updates

Resolve the highest stable version tag published for each Bosca image directly
from its container registry. Moving aliases such as `latest` and prerelease tags
are excluded. The commands use public GHCR images by default; they also support
Bosca Artifacts registries.

For an existing Swarm configuration:

```bash
bosca swarm update-images --config ./swarm.local.json --check
bosca swarm update-images --config ./swarm.local.json
bosca swarm deploy --config ./swarm.local.json
```

`update-images` saves version pins locally. It keeps each configured image
repository, infrastructure images, root website images, and secrets. Repeat
`--image` to update selected Swarm image keys, for example `--image server
--image runner`. Use `--registry artifacts.example.com/bosca` to move the
selected Bosca images to another registry and namespace. Registry credentials
come from the matching `registryAuth` entry in the Swarm configuration. All
selected lookups must succeed before any new image pins are saved. Encrypted
configurations remain encrypted.
An explicit `http://` or `https://` registry transport is saved separately in
`registrySchemes`, keyed by hostname and optional port, so later updates can
reuse the registry without repeating `--registry`. Docker image references
remain in the ordinary `host/namespace/image:tag` format.

For Helm, write a values file and pass it after your deployment values:

```bash
bosca helm images --output ./images.yaml
helm upgrade --install bosca ./helm/bosca-services \
  -f ./deployment-values.yaml -f ./images.yaml
```

For a subset of the umbrella chart, repeat `--service` with the subchart names:

```bash
bosca helm images --service bosca-server --service bosca-runner \
  --service tf-serving --output ./images.yaml
```

Only selected services are queried and pinned; unavailable images for other
services do not block the command. A lookup failure for a selected service
fails the command and preserves any existing output file. Omit `--service` to
resolve all Bosca services. The file sets `global.imageRegistry` for the release;
when updating a subset, choose the registry already used by your deployment.

The default chart is `bosca-services`. Use `--chart bosca-server` (or another
individual Bosca chart) for standalone values. `--chart tf-serving` updates only
the Bosca model-loader sidecar. Image settings include the registry, repository,
version tag, and an empty digest override so an older digest does not mask the
resolved tag. Service enablement and upstream infrastructure images remain in
your deployment values.

To query a private registry, pass `--registry artifacts.example.com/bosca
--registry-username api_token` and set `BOSCA_REGISTRY_PASSWORD`; use
`--registry-password-env` to choose another environment variable. The generated
Helm values contain no credentials; configure Kubernetes image pull secrets
separately. Omit `--output` to print the values to standard output.

## Child tasks through MCP

The conversation-bound MCP server exposes `bosca_delegate` for managed Kit child
tasks. `settings` lists approved profiles and resource budgets. `start` takes an
`input` object with a stable `requestId` UUID, `brief`, `expectedResult`, `profile`,
and optional `context`, `artifacts`, `scopes`, `runtimeSeconds` and `deadline`.
Reuse the same UUID and exact input after an uncertain response. `list` accepts
`offset` and `limit`; `get`, `wait` and `cancel` take the task `id`.

`wait` polls short reads for about twenty seconds. `QUEUED` and `RUNNING` mean the
task is unfinished; wait again on its existing ID. A child proposes structured
output through `submit` with `output: {summary, artifacts: [{metadataId, label}]}`.
The native terminal receipt confirms its outcome. Partial results remain marked
as partial on unsuccessful tasks. Cancellation does not undo completed actions.
The CLI binds every operation to its runtime conversation and scoped credential.
See [managed child policy and lifecycle](../ai/kit/runtime/README.md#managed-child-tasks).

## Prerequisites

- Java 25
- GraalVM (for native image)
- Workspace Gradle wrapper

## Build

Run from the workspace root:

```bash
./gradlew :cli:build                    # CLI build with tests
./gradlew :cli:run --args="--help"      # Run CLI via JVM
./gradlew :cli:nativeCompile           # Build cli/build/native/nativeCompile/bosca
./gradlew :cli:installDist             # Create distribution in cli/build/install/bosca/
./gradlew :cli:test                    # Run CLI tests
./gradlew :cli:distNativeMacos         # macOS: sign (Developer ID) + notarize the native binary
```

## Container image

The CLI Dockerfile packages a Linux `amd64` CI agent image. CI publishes it to
Bosca Artifacts as `<registry>/bosca/bosca-cli:<version>`. Start its
[image release pipeline](../.bosca/pipelines/release-image-bosca-cli.yaml) manually from an
existing Git tag; the selected tag supplies the image version:

```bash
docker run --rm <registry>/bosca/bosca-cli:<version> --version
docker run --rm <registry>/bosca/bosca-cli:<version> ci agent start
```

`ci agent start` is the default command, so the second invocation can be
shortened to:

```bash
docker run --rm <registry>/bosca/bosca-cli:<version>
```

The image runs as the `bosca` user (UID/GID `10001`) and includes Zulu JDK 17
and 25, Oracle GraalVM for JDK 25 with `native-image`, Node.js 22 with Corepack, and
the native compilation toolchain. Android builds are supported with the Android
SDK command-line tools, platform-tools, API 36 and 37 platforms, and Build Tools
36.0.0 and 37.0.0. It also includes the baseline build tools used directly by the
agent: Bash, Git, curl, CMake, Ninja, Python 3, jq, rsync, archive tools, and the
Docker client. Zulu 25 is the default
`JAVA_HOME`; Gradle can discover JDK 17 for Android toolchains, GraalVM is
available through `GRAALVM_HOME`, and the Android SDK is configured through
`ANDROID_HOME` and `ANDROID_SDK_ROOT`.

The image does not start a Docker daemon. For container-backed pipeline steps,
connect it to a daemon such as a Kubernetes DinD sidecar with `DOCKER_HOST`.

Persistent polling agents use the ordinary registration flow. Store these two
local files in a Kubernetes Secret:

- `~/.bosca/agent.json`
- `~/.config/bosca/config.json`

Mount them at `/home/bosca/.bosca/agent.json` and
`/home/bosca/.config/bosca/config.json`. The image uses `tini` as PID 1 so
Kubernetes termination signals reach the agent and its shutdown hook.
The agent's `workDir` setting in `agent.json` controls job files and the local
cache. Give agents on the same filesystem different `workDir` values; its
default is `/tmp/bosca-ci`.

Queue-driven Kubernetes CI Jobs do not mount a persistent agent registration.
Their `JobProfile` supplies the Bosca endpoint, and the dispatcher injects a
unique ephemeral agent ID, target job ID, and expiring job-scoped API token for
each execution.

To build the image locally on Linux after `:cli:nativeCompile`, stage the binary in
the layout expected by the CLI Dockerfile. Run from the workspace root:

```bash
mkdir -p cli/artifacts/local/bosca-cli-native
cp cli/build/native/nativeCompile/bosca cli/artifacts/local/bosca-cli-native/bosca
docker build --build-arg ARTIFACT_SHA=local -t bosca-cli:local cli
```

## macOS signing & notarization

To distribute the `bosca` binary without users hitting Gatekeeper's "developer
cannot be verified" prompt, sign it with a Developer ID certificate and notarize
it:

- `./gradlew :cli:distNativeMacos` — signs + notarizes the bare binary (Gatekeeper
  verifies online on first launch; ideal for tarball/Homebrew distribution).
- `./gradlew :cli:distNativeMacosPkg` — produces a signed, notarized, **stapled**
  `.pkg` installer that works fully offline (installs to `/usr/local/bin`).

The [CLI release pipeline](../.bosca/pipelines/release-cli.yaml) builds the Linux
tarball and the signed, notarized macOS installer and publishes both packages and
`SHA256SUMS` to Bosca Artifacts at
`<registry>/raw/bosca/bosca-cli/<version>/<filename>`.
CI publishes to Bosca Artifacts. To sync completed CLI versions to GitHub, install
**Default Artifact Sync Pipelines** version **1.1.0** and open the raw
`bosca/bosca-cli` repository's **Settings** in Studio. Add and enable a GitHub
release destination for `bosca-io/bosca`, its numeric repository ID, an empty
tag prefix, and a token secret with Contents read and write permission. The bare
`<version>` tag, such as `1.3.3`, must already exist on GitHub at the build commit. Completion
syncs both packages, `SHA256SUMS`, and `install.sh` together through the artifact
publication pipeline. See [artifact syncing](../artifacts/README.md#github-release-publication)
for configuration and status.

To forward an existing completed CLI version, use **Publish release** beside its
enabled GitHub destination in Studio. Select the version and supply its exact
build commit SHA; the `<version>` tag must already point to that commit.
The artifacts service queues a durable publication run using the stored files.
For an older stored version without completion history, review its files and
use **Confirm completed uploads** in that dialog before publishing. This records
the build commit without rebuilding; enabled destinations can publish automatically.

One-time certificate, credential, and CI-agent setup is documented in
[MACOS_SIGNING.md](MACOS_SIGNING.md).

## Architecture

This is a single-module application built on the Clikt command framework for consistent arg parsing, help text, and subcommand nesting. GraphQL operations in `src/main/graphql/` are compiled into typed Kotlin query/mutation classes by the Apollo Gradle plugin. Infrastructure manifests are generated programmatically using cdk8s rather than raw YAML templates. The `graalvmNative` plugin produces a self-contained `bosca` binary with `--no-fallback`. Embedded MCP and ACP servers provide agent-tool and editor integration.

## Dependencies

- `io.bosca:core`, `io.bosca:core-security`, `io.bosca:core-graalvm` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`
- `io.bosca:core-content` -- from bosca-content
