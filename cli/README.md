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
bosca version --check      # check the website's configured CLI release source
```

The CLI also prints a one-line "update available" notice (at most once per day,
interactive terminals only) when a newer release is published. Disable it with
`BOSCA_NO_UPDATE_CHECK=1` (or `BOSCA_UPDATE_CHECK=0`).

Release checks default to `https://bosca.io/cli/releases.json`, which uses the
same repository as the website's configured installer. `BOSCA_CLI_ARTIFACTS_URL`
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

### Kit through MCP

`bosca mcp-server` exposes Kit as a conversational tool:

- `kit_chat` — ask Kit natural-language questions, start or continue sessions,
  inspect history, and delete sessions. Kit can answer analytics questions and
  return structured visualization data. Generated images are returned as MCP
  image content; pass `downloadDirectory` to save them immediately.
- `kit_download` — download a Kit-generated asset by its returned `metadataId`
  to an explicit local path. Existing files are not replaced unless
  `overwrite=true`.

Reuse the `sessionId` returned by `kit_chat action=ask` for follow-up questions
that need the same conversational context.

### Kit through ACP

`bosca acp-server` exposes Kit as an ACP v1 agent over standard input and
output. ACP clients can create a conversation or load an existing one; the ACP
session ID is the durable Kit chat session UUID. Kit responses are returned as
text, JSON resources for structured analytics results, and image content for
generated assets. Client cancellation stops the active Kit turn.

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
`truncated` metadata. Configure the MCP server using the same saved profile:

```bash
BOSCA_PROFILE=work bosca mcp-server
```

## WorkOps through MCP

`bosca mcp-server` exposes `workops_plan` for planning, review, resumption, and
validation using WorkOps specs, rich documents, requirements, linked tasks,
references, and comments. Start with `action=instructions` for the workflow.

Create a complete plan in one tool call:

```json
{
  "action": "create",
  "requestId": "<caller-generated UUID; reuse for retries>",
  "projectKey": "WORK",
  "name": "Improve search filtering",
  "markdownContent": "## Goal\nSupport the requested filter in the existing search UI.\n\n## Approach\nFollow the current query and permission checks.",
  "requirements": [
    {
      "name": "Apply the filter",
      "markdownContent": "Implement the filter and add focused regression tests.",
      "acceptanceCriteria": [
        "Matching results are returned",
        "Inaccessible results remain excluded"
      ]
    }
  ]
}
```

Creation is one backend transaction: the spec, its rich document, requirements,
and populated linked tasks commit together. `requestId` is a caller-generated UUID;
reuse it with identical input to retry a lost response. Reusing it with different
input fails. Requests are scoped to the authenticated principal.

Review the resulting plan before beginning execution. `action=context` accepts
the returned spec `id` or `key` and returns its document, requirement criteria,
linked task progress and transitions, references, recent comments, and generated
tasks. Follow the returned `nextOffset`, `nextCommentOffset`, and
`nextGenerationOffset` for larger plans. `limit` defaults to 50 and supports
1–100 entries per page.

After reviewing the content, call `action=review` with the spec `id` or `key` and
an optional `reviewNote`. It stores a review baseline in a spec comment and returns
`reviewId`; it does not grant execution approval or change status. `action=diff`
compares the current plan with the latest readable review, or a supplied
`reviewId`. It reports changed scope, source bindings, references, documents,
requirement additions/removals/order, and acceptance criteria, including readable
before/after text and changed lines. Changes with unchanged visible text retain their rich JSON
for inspection. Missing or inaccessible documents fail comparison instead of
being reported as removals.

Start implementation by drafting interfaces, models, signatures, documented
behavior, and representative callers in the owning module. Present and iterate
on those contracts before implementing behavior. Include the current contracts
in the rich spec document, then record their review separately:

```json
{
  "action": "review_interfaces",
  "id": "<spec UUID>",
  "interfaces": [
    {
      "path": "search/core-search/src/main/kotlin/SearchService.kt",
      "declaration": "interface SearchService { suspend fun search(input: SearchInput): SearchResult }"
    }
  ],
  "reviewNote": "Record the interface discussion, corrections, and representative caller checks here."
}
```

Declarations and review evidence are caller-supplied; the MCP does not read
repository files or establish human approval. A plan review alone does not count
as an interface review. Plan edits invalidate interface review. When source
contracts change, update the plan and repeat interface review; a source-only
change cannot be detected by this tool. If the user requires sign-off, obtain it
before implementing behavior and record it honestly in the review note.

`action=next_action` recommends reviewing a new or changed plan, designing and
reviewing interfaces (`DESIGN_INTERFACES`), continuing an
unblocked task already in progress, starting the next unblocked task through an
available transition, resolving blockers, or running verification. It considers
both requirement-linked and additional generated tasks and existing `BLOCKS`
links. Inaccessible dependency endpoints are treated as unresolved. It returns
suggested tool arguments but never transitions or executes work.

Use `update` to revise the spec body, `add_requirement` (with a new `requestId`)
to add a deliverable atomically with retry protection,
and `update_requirement` to replace a requirement's body and acceptance criteria.
Requirement writes replace the document and linked task's summary and independent
Markdown description in one transaction, checking requirement, task, and metadata
versions. A conflict rolls back both writes. The existing `workops_requirement`
tool provides the same behavior through `action=set_content` with `id` or `key`, `name`,
`markdownContent`, and `acceptanceCriteria`. Direct `content_metadata` edits
continue to affect only the document; custom document nodes use `documentContent`
and require an explicit task-description update when applicable.

Discover actual workflow transitions using `workops_spec action=transitions` and
`workops_task_transitions`, then transition the spec and linked tasks as work
begins and finishes. Workflow names are configurable. Requirements already have
linked tasks; `generate_tasks` optionally creates additional tasks.

Use TDD for each behavioral increment. Write a focused test and observe its
intended failure before implementing behavior (RED), implement the smallest
passing change (GREEN), then refactor and rerun relevant tests (REFACTOR). Repeat
for each increment. Compilation errors, unavailable infrastructure, and unrelated
failures do not establish RED. Record the actual commands and observations as
they happen; do not invent a prior failure after implementation. If no refactoring
is needed, record the review and passing rerun.

Define the test strategy and coverage scopes during interface review. Aim to
cover every changed behavior. The default minimum is **99% line coverage and
97% branch coverage**, preserving stricter owning-module gates. Run the owning
module's reports and verification, inspect changed code in those reports, and
record all affected production scopes so an aggregate cannot hide missing tests.
Review uncovered paths explicitly; never lower thresholds, exclude handwritten
production code, or narrow scope to inflate coverage.

Tests must assert observable behavior and contracts and fail when the behavior
is wrong. Cover relevant success, boundary, empty/null, invalid-input, denied
permission, failure/cancellation, conflict, rollback, retry, and concurrency
paths. Use isolated, deterministic fixtures. Mock-call counts and assertions that
repeat private implementation do not establish behavioral quality. Wiring, SQL
mapping, transactions, migrations, and cross-module dispatch need real integration
tests with the established infrastructure where applicable. Very high coverage
alone does not establish test quality.

After completing a linked task and running its checks, record the evidence:

```json
{
  "action": "record_validation",
  "id": "<spec UUID>",
  "requirementId": "<requirement UUID>",
  "checks": [
    {
      "criterion": "Matching results are returned",
      "outcome": "PASSED",
      "evidence": "Focused search regression test passed.",
      "tdd": {
        "red": {
          "command": "<focused test command run before implementing behavior>",
          "outcome": "FAILED",
          "evidence": "<expected assertion failure demonstrating missing behavior>"
        },
        "green": {
          "command": "<focused test command>",
          "outcome": "PASSED",
          "evidence": "<observed pass after minimal implementation>"
        },
        "refactor": {
          "command": "<relevant test command after refactoring>",
          "outcome": "PASSED",
          "evidence": "<observed pass after refactoring or review with no change needed>"
        }
      }
    },
    {
      "criterion": "Inaccessible results remain excluded",
      "outcome": "NOT_RUN",
      "evidence": "Required test infrastructure is unavailable."
    }
  ],
  "testQuality": {
    "review": "Describe the behavioral assertions, boundary/failure cases, applicable integration tests, fixture isolation, and remaining limitations.",
    "coverage": [
      {
        "scope": "<affected production module and changed classes>",
        "command": "<exact test, coverage report, and verification commands>",
        "report": "<path to the measured coverage report>",
        "lineCovered": 990,
        "lineMissed": 10,
        "branchCovered": 970,
        "branchMissed": 30,
        "uncovered": "<identify remaining uncovered lines/branches and review changed paths>"
      }
    ]
  }
}
```

Copy normalized criterion text from `context`; outcomes are `PASSED`, `FAILED`,
or `NOT_RUN`. A `PASSED` check requires a current interface review and TDD runs
with RED=`FAILED`, GREEN=`PASSED`, and REFACTOR=`PASSED`, each with a nonblank
command and observed result. Failed and unrun checks may report incomplete
cycles; they cannot establish completion. `PASSED` also requires `testQuality`
with a review and nonempty coverage reports. The example counts above illustrate
99% lines and 97% branches; replace them with actual raw counters from each report.
The tool derives percentages from those counters without rounding to meet a gate.
No executable lines cannot establish coverage. A report with no branches must
have `branchCovered=0`, `branchMissed=0`, and a nonblank `noBranchesReason`
explaining that measured scope; its branch percentage is reported as unavailable,
rather than inventing a percentage. `context` and `verify` expose `coverageSummary`.
Evidence is stored in a readable spec
comment tied to the current plan and requirement content, linked task version,
and interface review. `action=verify` reads every
page and reports missing criteria, absent/failed/unrun or stale evidence,
missing/stale interface reviews, absent/invalid TDD cycles, missing test-quality
reviews, invalid/missing coverage counters or coverage below the minimums, incomplete
linked/generated tasks, and incomplete child specs. It returns
`readyToComplete` only when these checks pass. It records caller-supplied
evidence; it does not read coverage files, audit test quality, run tests,
independently establish phase chronology, or
transition workflow states. Earlier passing records without TDD evidence or
measured coverage cannot establish completion under this workflow. Backend transitions
into DONE enforce the same effective policy, so a direct workflow mutation cannot bypass
the evidence checks. Rejected completion leaves the spec status and history unchanged. Unavailable
infrastructure, failed owning-module gates, and unmeasured coverage must remain
unverified; never substitute passing test counts for measured coverage. Rerun
reports after source or test changes. Review that
evidence before marking the spec done.

The new atomic operations require a matching backend deployment and the registered
WorkOps migration `V56__plan_creation.sql`. Independent low-level operations keep
their existing semantics. These tools do not silently fall back to separate writes
against an older backend.

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
CI publishes to Bosca Artifacts; external forwarding belongs to the artifacts server.
One-time certificate, credential, and CI-agent setup is documented in
[MACOS_SIGNING.md](MACOS_SIGNING.md).

## Architecture

This is a single-module application built on the Clikt command framework for consistent arg parsing, help text, and subcommand nesting. GraphQL operations in `src/main/graphql/` are compiled into typed Kotlin query/mutation classes by the Apollo Gradle plugin. Infrastructure manifests are generated programmatically using cdk8s rather than raw YAML templates. The `graalvmNative` plugin produces a self-contained `bosca` binary with `--no-fallback`. Embedded MCP and ACP servers provide agent-tool and editor integration.

## Dependencies

- `io.bosca:core`, `io.bosca:core-security`, `io.bosca:core-graalvm` -- from bosca-core
- `io.bosca:di`, `io.bosca:service` -- from `services-di`
- `io.bosca:core-content` -- from bosca-content

### Configuring WorkOps instructions

Open Studio → WorkOps → Settings → Guidance to edit investigation, specification,
interface, implementation, and verification guides, with platform defaults and project
overrides. The typed policy controls required interface review, TDD evidence, test quality,
and minimum line/branch coverage (defaults: 99%/97%). Rich documents are authoritative;
SKILL.md import/export makes individual guides portable without changing policy.

`workops_spec_instructions` and `workops_plan action=instructions` load the current
configuration. Pass `projectId` or `projectKey` for project guidance; omit both for
platform defaults. Plan context, reviews, recommendations, and verification use the
spec's project. A changed revision, including explicit override state, makes earlier reviews
and validation stale. Studio preserves drafts during scope changes and pending saves.
Saving or restoring defaults uses the loaded revision and rejects concurrent changes;
use Reload and reconcile to preserve the draft and resolve conflicting saved changes before retrying.
Identical saves retain revisions, and overridden platform changes do not invalidate project evidence.
Resetting or inheriting a guide also
removes its obsolete AgentResource.
Update the backend and CLI together; no MCP restart is needed for subsequent edits.
