# Kit runtime

The existing native Bosca CLI runs the supervisor with `bosca harness serve`.
The image contains the CLI, the pinned native harness adapter, and an init process.

For Docker Swarm, use the [manager controller and runtime profile](SWARM.md).

Standard deployments generate this configuration. In the published Helm services
chart, set `global.kit.enabled: true` and choose `global.kit.harness` (`codex` or
`claude`); the provider Secret defaults to `openai-token/token` or
`anthropic-token/token`. In a Swarm site, set `kit.enabled: true`, choose
`kit.harness`, and supply `secrets.kitProvider`. Run the normal platform deployment.
The chart/installer supplies matching runtime identities, profiles, and workload
defaults. A private policy is optional. The detailed contracts below support
custom deployments and explain those generated settings.

The supervisor keeps one native ACP process and session alive across Runner connections and turns. It retains the negotiated ACP initialization and session setup for reconnecting clients. Native process shutdown happens during idle archival, managed retirement, explicit conversation deletion, or container shutdown.

Runtime control requests require a generation-specific bearer token, `X-Bosca-Generation`, and the current `X-Bosca-Epoch`. `/v1/claim` fences a previous Runner connection. Mutations recheck ownership inside the runtime lock, including requests delayed while reading their bodies. `/healthz` and `/readyz` expose only status codes. The authenticated WebSocket `/acp` carries one JSON-RPC message per text frame.

Each supervisor process generates a fresh `instanceId`. The initial `/v1/claim`
returns it; Runner persists it before hydration, native startup or ACP setup.
Subsequent control and WebSocket requests carry `X-Bosca-Instance`. A different
process rejects those requests even when it has the same generation's mounted
credentials. An initial claim can be retried before its identity is persisted;
it does not authorize hydration or native work without the instance header.

Runner checks the admitted identity on reconnection and while renewing ownership
of a long active turn. These HTTP probes run outside the renewal's database
connection context. Replacement retires the generation: Bosca revokes its scoped
credential before requesting workload removal, interrupts an uncertain sent turn
without replay, and retains queued messages and the last committed archive.
Retry creates a fresh generation with new credentials and current context.
Process identity is never restored from an archive. The same fence applies to
process replacement behind any infrastructure endpoint.

The Kubernetes profile uses `ttlSecondsAfterFinished: 0` so failed generations
and their owned Services and Secrets are collected after the Job ends. The
controller's result finalizer keeps the Job until its terminal result is durable
in Bosca. A nonzero TTL also retains those generation resources for that period;
existing Kit profiles should use zero for prompt cleanup after runtime failure.

Deploy `V9__kit_runtime_instance.sql` with matching API, Runner and runtime images.
The migration leaves unknown identities unset. Before upgrading an existing warm
deployment, finish active work and archive its runtimes with the previous release;
then update the matched artifacts and reactivate from committed archives. An old
generation is never rebound to a replacement process to preserve continuity.

Bosca accepts messages into ordered PostgreSQL turns. A runtime journals a durable turn ID before forwarding its prompt and rejects repeated submission of that ID. Runner recovery reads `/v1/turns/{id}`; an unknown outcome is interrupted without replay. Permission requests carry their own stable request identities and decisions remain durable in Bosca.

Each generation retains at most `maxJournalBytes` of receipts (64 MiB by default)
and `maxJournalTurns` accepted turn IDs (256 by default). At half the byte budget,
or at the turn limit, it finishes the active turn and refuses new prompts. Runner
then uses the normal verified archive and retirement path; queued messages resume
in a fresh generation. Receipts and duplicate-turn protection remain available
until the workload is removed. Starting retirement at half capacity leaves room for
an accepted turn to finish, but does not guarantee that an individual turn will fit.

If a turn exceeds the remaining byte budget, or one native output line exceeds
the full budget, the supervisor stops the native process and reports a journal
limit failure. Runner records the uncertain turn as interrupted without replay,
preserves queued messages and retains the last committed checkpoint. Local changes
since that checkpoint can be lost. Retry session resumes queued work. No partial
receipt is presented as a completed turn. Limits are deployment profile settings;
`maxJournalBytes` accepts 1 KiB–256 MiB and `maxJournalTurns` accepts 1–10,000.

Studio receives coalesced text, plan, tool and resource updates through the existing chat subscription. Each turn updates one display message, with generation, ownership epoch and revision ordering to handle reconnects. Completed text also enters canonical conversation history. Tool arguments and private reasoning are not streaming display fields. The supervisor redacts credential values from delivery and receipts before persistence.

Native permission requests appear in Studio with the harness's offered options. Decisions are bound to the requesting principal, turn, generation, policy revision and operation digest. Bosca checks the policy revision, current group membership and any custom-setting permission both when accepting a decision and before delivering it to the harness. A replaced operation, expired request, changed authority or completed turn cannot reuse a decision. A disconnected Runner reconciles the same durable request; a stored decision remains in the audit record but is delivered as cancelled if its policy or authority changed.

Stop cancels work already accepted for the conversation and sends native `session/cancel` for the active turn. Permission waits are settled as cancelled. Messages accepted after Stop remain queued. Bosca waits for a terminal response; after `cancellationSeconds` without confirmation it removes the runtime and records an interrupted outcome. Cancellation does not undo completed operations or replay an uncertain turn. A later message restores the last committed archive and supplies canonical context.

## Archive and restore

The fixed writable roots are `/workspace`, `/state/harness`, and `/state/scratch`. Codex uses `CODEX_HOME=/state/harness`. Claude uses `CLAUDE_CONFIG_DIR=/state/harness` and `CLAUDE_CODE_TMPDIR=/state/scratch`. The supervisor stops the native process and remaining writers in its private PID namespace before making a gzip tar archive with a JSON manifest. The container must run under the supplied `tini` entrypoint in its own PID namespace.

The archive retains the complete native operational directory, workspace, and persistent scratch as opaque files, including databases, journals, settings, plugin data, and files introduced by a newer harness. Known provider-login files (`auth.json` for Codex and `.credentials.json` for Claude) and transient IPC sockets are excluded. Runtime receipts and regenerated managed connections stay outside the native directory. Restore checks archive and per-file hashes, manifest membership, harness family, conversation identity, paths, file types and size limits before populating the fixed roots. A failed file installation clears the partial restore so it can be retried. Unsupported links or filesystem entries fail archival visibly.

Upgrading the same harness family's image preserves its native session and operational directory. The new harness opens its own files and performs any native migrations it supports, as with a local harness upgrade. Bosca does not interpret database schemas or maintain per-version filename allowlists. A native migration failure is reported rather than silently replacing the saved session. Cross-family switches use a fresh native session.

Fixed mount roots keep their deployment permissions. Restore applies archived modes
to files and subdirectories without changing the mount itself. This supports a
non-root harness writing to a root-owned Kubernetes volume through `fsGroup`.

Runner uploads through `ObjectStorageService`, reads the object back to verify its size and checksum, and commits the archive reference under the current owner before requesting workload removal. Upload failure leaves the local archive and previous committed checkpoint intact. Messages accepted after STOPPING remain queued through restore. Unused native sessions start a new native conversation after restore because there is no native prompt history to load.

`/state/control` and `/state/home` are regenerated. `bosca harness mcp bosca` reads the current generation's private connection settings and starts the CLI's shared MCP server. This supplies `BOSCA_TOKEN`, `BOSCA_ENDPOINT` and `BOSCA_MEMORY_SESSION_ID` even when the native MCP launcher filters inherited environment variables. The conversation ID lets memory tool reads participate in checkpoint invalidation. Credentials are neither ACP parameters nor archive entries. The runtime-owned scoped token is revoked when its generation is removed.

## Archive storage and retention

`maxArchiveBytes` bounds an individual archive. The deployment's
`maxArchiveBytesPerPrincipal` defaults to 64 GiB and counts all reserved private
objects for that principal: current checkpoints, uploads and objects awaiting
confirmed deletion. A generation reserves its compressed receipt size before
uploading. Reservation and quota checks serialize in PostgreSQL across Runner
instances and conversations. Retrying the same immutable receipt uses its existing
reservation. Lowering the limit prevents new reservations without discarding
already admitted objects.

Replacing a checkpoint needs capacity for both the previous object and its
replacement until verification and commitment succeed. If quota is full, archival
retains local state and the previous verified pointer, leaves accepted messages
queued and exposes a storage failure in the conversation. Increase the managed
limit or delete an unused conversation to free space. Recovery retries the same
generation; it does not repeat accepted prompts. The generation's hard deadline
continues to apply while archival is blocked.

`archiveSeconds` defaults to 600 seconds for each archive creation/transfer/
verification attempt. A failed or timed out upload retains its reservation until
it is successfully retried or collected after that generation is retired.
Successful commitment and workload removal make superseded objects eligible for
deletion. The durable inventory retains their charge until object deletion succeeds.
A failed cleanup is visible and retried even for a stopped or failed session;
workload capacity has already been released. Purge follows the same object inventory.

Studio refreshes warm and retiring runtimes while the conversation is open, and
continues while stopped-session cleanup reports a recoverable failure. It stops
after clean archival. These reads expose lifecycle progress and recovery without
sending activity mutations or extending the runtime idle deadline.

`maxArchiveAgeSeconds` is optional. Its default is null, so intentionally long
returns keep their current checkpoint until conversation deletion. An explicitly
configured positive age expires cold native state and saved workspace. Recovery
clears the pointer and collects the object; a later activation receives a fresh
native session with current permitted history, memories and sources. The
conversation records a continuity notice. An archive with an unknown creation
time is ineligible for restore when an age policy requires that timestamp.
Current profile settings are checked again before activation. Expiry does not
execute a prompt, extend the idle deadline, or delete independently saved memories
and published content.

Deploy the registered `V8__kit_archive_retention.sql` migration with matching API
and Runner artifacts. It inventories possible earlier generation objects using the
profile's size upper bound where no measured size exists. It preserves unknown
creation times, and obsolete or absent objects release their reservations through
normal collection. No shared block volume or separate storage service is required.

## Build and configure

The release script builds the native CLI from the same checkout, stages it by
content hash, and tests the resulting runtime image before publishing. Run it on
a Linux x86_64 host from the workspace root:

```sh
PUSH=false scripts/release/build-image.sh kit-codex-runtime <version>
PUSH=false scripts/release/build-image.sh kit-claude-runtime <version>
```

The corresponding image release pipelines run the same gate and retain
`ai/kit/runtime/artifacts/release-<version>/compatibility.json` as a job artifact.
It records the image reference, published repository digest, local image identity,
platform, CLI version/SHA-256 and the locked adapter/harness/protocol/archive
descriptor. `PUSH=false` produces the same record with a null repository digest;
its local Docker identity is not a registry pin. A publishing run fails if Docker
does not report a digest for the matching repository.

To check an already built image independently:

```sh
KIT_RUNTIME_IMAGE='<image>' KIT_RUNTIME_FAMILY=codex KIT_RUNTIME_VERSION='<cli-version>' \
  ./gradlew :ai:kit:runtimeImageTest
node --test scripts/release/build_image.test.mjs
```

The image test uses the installed native CLI and package manifests with an ACP
fixture. It checks authentication, generation and owner fencing, native GraphQL
MCP discovery, execution and cancellation, tool registration, warm process reuse,
native approvals and cancellation, termination
of detached writers before archival, and restoration into a new container with
current credentials. The container uses a read-only root filesystem, an
unprivileged user, dropped capabilities and a private PID namespace. It requires
Docker and makes no provider inference requests. This gate runs on every release
invocation and is separate from the ordinary JVM suite and provider qualification.

To supply a separately qualified CLI, use a Linux Bosca CLI image pinned by digest. It must include
the `bosca_memory` tool and its conversation read tracking:

```sh
docker build --platform linux/amd64 --build-arg BOSCA_CLI_IMAGE='<qualified-cli-image>@sha256:<digest>' \
  -t kit-codex-runtime ai/kit/runtime
```

The build pins the Codex adapter and native harness through `package-lock.json`. The supervisor is part of the supplied Bosca CLI image. `compatibility.json` records the protocol, native versions, archive policy and fixed paths. Publish and deploy the resulting image by digest through the deployment's normal release process.

The same Dockerfile has a `claude` target using the independently locked
`claude/package-lock.json` and compatibility manifest:

```sh
docker build --platform linux/amd64 --target claude \
  --build-arg BOSCA_CLI_IMAGE='<qualified-cli-image>@sha256:<digest>' \
  -t kit-claude-runtime ai/kit/runtime
```

The Kotlin supervisor reads the image's manifest and verifies both installed
package versions, protocol versions and mount layout before opening its server.
Its reported identity comes from those verified packages. Deployment profiles
must pin that identity and the qualified image digest. Changing an environment
variable cannot change the checkpoint family reported by the supervisor.

For Claude, use a separate approved JobProfile with the Claude image and an
`ANTHROPIC_API_KEY` Secret reference. Set `authenticationMethod: null` in the Kit
deployment profile: the pinned adapter reads unattended provider credentials from
its environment and does not advertise Codex's `api-key` ACP method. Do not mount a
developer login directory. Scoped Bosca credentials use the existing generation
lifecycle. Model controls use advertised ACP `model` and `thought_level`
categories, retaining each adapter's opaque selector IDs inside the connection.

Claude packaging and local contract checks do not establish live-provider
certification. Qualify the actual image with the supported provider account before
enabling its profile. Both harnesses share the same turn/history/context/memory
and checkpoint lifecycle; their native state policies remain distinct.

Adapt [job-profile.example.yaml](job-profile.example.yaml) in the deployment's operations repository. Supply the provider credential using a Kubernetes Secret. The controller creates a separate immutable Secret for the generation's Bosca and control credentials, plus a Job-owned internal Service. The `bosca-services` chart adds network policies that let runtime Pods accept connections only from the Runner and connect only to cluster DNS, the Bosca API and the Kit egress proxy, which permits Bosca's public hostnames, the profile's provider hosts, the session agent's remote MCP registrations and the `egressHosts` in the private `kit.harness` policy. The policies take effect only with a network plugin that enforces NetworkPolicy. A controller chart installed by itself renders no proxy or policies; supply equivalent ones for a custom installation.

Both API server and Runner need the same `kit.harness` configuration; the Runner must process the existing content queue and enable Kubernetes providers. For a Runner in the workload's cluster:

```yaml
kit:
  harness:
    maxRuntimes: 10
    maxRuntimesPerPrincipal: 2
    profiles:
      - profile: kit-codex
        image: "ghcr.io/bosca-io/bosca/kit-codex-runtime@sha256:<qualified-digest>"
        imageId: "<image-configuration-digest>"
        versions:
          cli: "<qualified-bosca-cli-version>"
          adapter: "2.2.2"
          harness: "0.160.1"
          statePolicy: "codex-home-v1"
          transport: 1
          acp: 1
          archive: 1
        endpointTemplate: "http://{job}.{namespace}.svc:8080"
        boscaEndpoint: "https://YOUR_BOSCA_API/graphql"
        authenticationMethod: api-key
        startupSeconds: 900
        connectionSeconds: 10
        readinessSeconds: 600
        restoreSeconds: 600
        approvalSeconds: 86400
        promptSeconds: 86400
        hardRuntimeSeconds: 86400
        idleSeconds: 900
        cancellationSeconds: 30
        credentialLifetimeSeconds: 86400
        maxArchiveBytes: 8589934592
        maxJournalBytes: 67108864
        maxJournalTurns: 256
        scopes: ["content:view", "content:edit", "content:delete", "collections:view", "collections:edit", "search:read"]
```

Each profile requires the qualified image and component versions above.
`authenticationMethod` selects an advertised ACP authentication method, such as
`api-key` for Codex. Null or omission uses the harness's environment-based provider
authentication without an ACP authentication request.

The controller records `image` from the running worker's PodSpec and `imageId` from
its kubelet container status. It accepts only a single running pod owned by the
exact Job UID, selects the dispatched worker rather than a sidecar, and clears
ambiguous observations. These values remain in the durable execution record.

Standard releases resolve immutable image references and configuration digests
from the registry and image-owned metadata. Kubernetes observations can identify
the admitted image by its pinned manifest digest or configuration digest, with
runtime prefixes normalized. Custom installations can supply the same metadata.
Provider and environment acceptance remain deployment checks.

The authenticated supervisor status reports the CLI's compiled build version,
the installed Codex adapter and harness package versions, the active state policy,
and its implemented protocol versions. Package metadata is read from the immutable
`/opt/runtime/node_modules` installation, independently of the expected deployment
values. Local fixture runs can set `BOSCA_RUNTIME_HOME` to their installation root.
Missing or malformed package metadata prevents supervisor startup.

Runner checks both Kubernetes image observations and supervisor identity before
restoring a checkpoint or starting native work. Missing image observations wait
within the startup deadline. Mismatches fail readiness, revoke the generation's
credential and remove its workload; queued messages and the last verified archive
remain available for Retry session. Updating an identity pin changes the existing
policy revision and retires active generations through the ordinary lifecycle.
Deploy the Kubernetes image-observation migration and matching controller, Runner,
and CLI together, with identity values configured in every Kit deployment profile.

`maxRuntimes` limits dispatched Kit generations across all profiles and principals;
`maxRuntimesPerPrincipal` limits generations for the initiating principal across
profiles. The defaults are ten and two, respectively, and both must be positive.
An admission check and dispatch reservation share a PostgreSQL transaction, so
concurrent Runner instances cannot exceed either limit. Admission happens before
credential issuance or Job dispatch. CPU, memory and writable-storage bounds stay
in the selected JobProfile and Kubernetes namespace quotas.

Dispatches waiting for Kubernetes capacity, warm sessions, approval waits and
workloads being removed all consume a slot. Empty sessions expire through the
ordinary idle lifecycle. A failed generation releases its slot only after
confirmed workload removal. Deletion retries release runtime capacity once the
workload is gone, even if archive deletion still needs recovery.

At capacity, a new runtime request reports a failure and retains its queued
messages and checkpoint. Use **Retry session** after another session is archived
or stopped, or after an administrator raises the limits. Admission failure does
not repeatedly allocate credentials or dispatch Jobs in the recovery sweep.
Changing these limits affects new admissions; existing generations and pending
approvals retain their policy and can continue. Lowering a limit blocks new
admissions until usage falls below it.

Choose scopes from Bosca's API-token scope catalog for the tools the deployment permits.
Runtime profiles reject administrative `:manage`/`:admin` scopes and `ai:execute`/`mcp:execute`,
with one explicit exception: a profile may include `scripts:manage` for script
management. Include `scripts:view` for discovery and `scripts:execute` for running
scripts as needed. These scopes remain intersected with the initiating caller's
scopes and effective groups; script ACLs still apply. Script management is not
added to profiles that omit it.

For pipeline tools, explicitly include `pipelines:view`, `pipelines:edit` and/or
`pipelines:execute` in the selected profile. Inspection, validation and saving
still require the caller's administrator group; these scopes do not confer that
role. Private execution retains pipeline EXECUTE permissions, and the run's
individual actions still require their own domain scopes. Existing scoped clients
using administrator pipeline reads/saves must add the corresponding pipeline scopes.

Durable pipeline runs capture the caller's effective groups, API-token scopes and
credential ID. Initial execution, backing work, resume and rollback recheck the
credential and membership; child runs and restarts retain the same restrictions.
An anonymous API call remains anonymous. Only platform-triggered and scheduled
runs use the pipelines service account. Expired, revoked or narrowed credentials
stop subsequent work; a broader credential does not expand an existing run.
Before applying `V22__pipeline_run_authority.sql`, let in-flight pipeline runs
finish and deploy matching API and worker artifacts. Stored runs without captured
authority cannot execute or restart; submit a new run under current authorization
when execution is needed. Existing run history remains readable.

API tokens used by external chat clients need `ai:execute` for chat queries,
mutations, and subscriptions; add that scope to their explicit token grants when
upgrading. Browser login behavior is unchanged. Harness tokens never receive this
scope, preventing access to other conversations and recursive Kit invocation.
The grant intersects the caller's scopes and groups.

Configure an endpoint reachable from the actual Runner placement; the template supports `{job}`, `{namespace}` and `{cluster}` and must resolve to the runtime's HTTP origin. Cluster-local DNS requires a Runner that can reach that cluster. The workload deadline must cover `hardRuntimeSeconds`. Managed retirement starts between turns five minutes before the runtime deadline, or ten percent of the lifetime for profiles shorter than fifty minutes; pending messages continue in the next generation.

`startupSeconds` bounds activation from the durable dispatch timestamp through native
session readiness. Its default is 900 seconds, and it must be positive and shorter
than `credentialLifetimeSeconds`. Queue waits, restore, MCP preparation and ACP setup
share that budget; queue redelivery and Runner replacement retain the original
deadline. Expiry records cleanup intent, revokes the generation credential and waits
for workload removal. The last committed checkpoint and unsent messages remain
available for **Retry session**. Failed revocation or removal retries cleanup.
Runner cancellation preserves the pending startup for recovery. Warm turns and idle
archival use their own lifecycle rules. Blocking storage connection setup remains
subject to the storage provider's I/O timeouts.

The profile also controls individual waits:

| Setting | Default | Budget |
| --- | --- | --- |
| `connectionSeconds` | 10 | One control request, including its response body, or opening an ACP WebSocket |
| `readinessSeconds` | 600 | One attempt to negotiate ACP, prepare MCP connections, open the native session and apply model settings |
| `restoreSeconds` | 600 | One attempt to read, verify and install a checkpoint |
| `approvalSeconds` | 86,400 | A native permission decision, starting when Bosca first records the request |
| `promptSeconds` | 86,400 | A prompt, from durable submission through its terminal response, including permission waits |
| `hardRuntimeSeconds` | 86,400 | A generation, from credential issuance before dispatch through shutdown |

Connection, readiness and restore settings accept 1–86,400 seconds; approval waits
accept 1–604,800 seconds. Readiness also applies when reconnecting before a warm
turn. Its timer ends before prompt submission. MCP preparation requests use
`readinessSeconds`; restore requests use `restoreSeconds`.

A connection or setup timeout leaves unsent work available for recovery. During
startup, retries retain the original generation and total startup deadline.
Overlapping native initialization or session setup is rejected while the first
request is pending; once it completes, reconnect uses its cached result. Parent
coroutine cancellation remains cancellation. Approval expiry is durable across
Runner replacement and returns a cancelled permission decision; a late user
decision cannot extend it. Archive preparation and transfer have a ten-minute
request budget.

Control status, durable turn-receipt reads and the fenced ownership claim retry
connection/read failures and HTTP 502, 503 or 504 responses for up to three client
attempts. The two waits use independent jitter of 100–200 ms and 200–400 ms. All
attempts and waits share one `connectionSeconds` budget; startup retains its
original dispatch deadline. The JDK transport can retry connections within an
attempt; the same overall deadline still applies. Each attempt carries the same
generation, epoch and control credential. Authentication failures, stale ownership, other HTTP errors,
TLS/protocol exceptions and invalid response data fail immediately. A missing turn
receipt remains missing and follows the existing interrupted-outcome path.
Lifecycle mutations, permission decisions and ACP prompts use their existing
durable reconciliation paths. An HTTP retry never resubmits a prompt. Parent
cancellation interrupts both a blocked request and a retry wait. Exhausted
requests propagate the final failure to the existing job queue; its ordinary
failure retry delay is 30 seconds times the job's failure count, while recovery
also republishes pending sessions.

The prompt deadline is stored on the turn before submission. Queueing and native
setup do not consume that budget, and reconnecting does not extend it. Runner
uses the existing native cancellation path when the deadline passes, including
when recovering a disconnected turn. Permission decisions cannot outlive the
prompt deadline. The cancellation grace period starts at the deadline, so a
replacement Runner cannot restart it. A confirmed cancellation closes the turn;
an unconfirmed one removes the runtime and records an interrupted outcome.
Other queued messages remain available.

`promptSeconds` and `hardRuntimeSeconds` must be positive; the runtime lifetime
cannot exceed `credentialLifetimeSeconds`. The generation's absolute deadline is
persisted in its existing encrypted control environment. Both Runner and the CLI
supervisor enforce it, so native work stops even while Runner is unavailable.
The supervisor uses the same process-tree shutdown as archival. Runner persists
cleanup intent, revokes the scoped credential and waits for workload removal
before recording the interrupted outcome. Cleanup retries do not require a live
control token. Unsent turns and the last committed archive remain available for
Retry session; local changes since that archive may be lost. A committed archive
can finish cleanup after the deadline. Scoped credentials are never refreshed.

For optional profile/model choices, Agent defaults, or delegation, create a private Configuration with key `kit.harness` in Studio's System → Configuration surface:

```json
{
  "defaultProfile": "kit-codex",
  "allowedProfiles": ["kit-codex"],
  "agentDefaults": {}
}
```

The Configuration's group ACL grants `EXECUTE` for per-chat profile choices and `MANAGE` for policy administration. Grant administrators `LIST` and `VIEW` for the existing configuration editor as appropriate. No special harness group is required. Ordinary authenticated callers receive the managed profile and a public summary; authorized callers receive only the allowed profile names. Scoped callers also need the existing `security:manage` scope for explicit private-Configuration permissions. This does not add that scope to the runtime token.

The policy revision is a SHA-256 content revision of the configuration identity, typed policy and authority-relevant deployment profile fields. Runtime images, placement, and operational budgets do not invalidate approved conversation choices. Runner records the revision, source, effective configuration and scope/group intersection on each turn before submission. It rechecks membership and policy between turns; an active turn keeps its snapshot. A stale or revoked override is cleared, managed defaults apply, and the conversation records the change. Agent defaults may select only policy-allowed deployment profiles. Model and reasoning choices use the policy and session capabilities described below.

An absent private policy uses the first configured deployment profile and grants no conversation overrides. An invalid or public policy prevents new turns. A prepared runtime archives before stopping; queued messages remain durable and Studio reports the policy failure. After repairing the configuration, reactivating the conversation resumes its pending messages.

A configuration or authority change drains the old runtime through verified archival and credential retirement. Same-family image/version and operational-budget changes preserve native state, session identity, and approved conversation choices. Profile, authority, integration, or model-policy changes restore eligible workspace files into a new native session with canonical context. Workspace restoration still validates the complete archive's hashes, identity, paths and manifest. The previous archive remains available until a new verified checkpoint is committed.

A runtime crash can lose native changes since its last committed archive. Bosca retains canonical history and durable turn outcomes, reports the interruption, and restores the last archive on re-engagement. The implementation does not replay uncertain prompts.

## Managed child tasks

Delegation is disabled unless the private `kit.harness` policy includes a
`delegation` value. Deploy matching API, Runner and runtime CLI builds with the Kit
migration before enabling it. Add `ai:delegate` to the participating runtime profiles' scopes
and explicitly approve the child profiles in the policy, for example:

```json
{
  "defaultProfile": "kit-codex",
  "allowedProfiles": ["kit-codex", "kit-claude"],
  "delegation": {
    "profiles": ["kit-claude"],
    "maxDepth": 1,
    "maxActive": 1,
    "maxChildren": 4,
    "maxRuntimeSeconds": 600,
    "totalRuntimeSeconds": 1800
  }
}
```

`bosca_delegate` is available through the conversation-bound MCP server. Its
`settings`, `start`, `list`, `get`, `wait`, `cancel` and `submit` actions use the
existing scoped GraphQL transport. General chat execution still requires
`ai:execute`; runtime profiles cannot receive it. The delegation API additionally
binds the credential ID to its current runtime and requires an active sent turn
for starting, waiting and submitting output.

A start reserves a stable request UUID, brief, expected result, selected sources,
profile, scope ceiling and fixed deadline in the same transaction as the child
conversation and queued turn. Repeating the UUID with identical input returns the
same task; changed input fails. The child uses `ChatSession.parentSessionId`, the
same principal, intersected groups/scopes, and only MCP connections already
selected by the parent. Each child has a separate runtime generation and writable
workspace. Selected source revisions are checked again before dispatch. Shared
memory and managed context follow their existing permission checks; the parent's
full conversation and writable checkout are not copied.

Depth, total child count, active child count and allocated runtime seconds apply
across the entire tree started by one parent turn. Completed or deleted children
retain their reservations until that turn is deleted. These are runtime resource
budgets; provider token usage and billing are not metered by this policy. The
fixed task deadline also bounds queueing, credentials and the native runtime and
cannot exceed the parent's current deadline. Ordinary deployment admission and
workload resource limits still apply.

Waiting records durable intent and detaches the Runner's ACP observer. The parent
keeps its native prompt and logical turn while its worker, connection and lease
are released. Later jobs inspect the same prompt receipt. The supervisor consumes
the SDK's request-cancellation notification when its
observer detaches; native turn cancellation uses the fenced control endpoint.
A reconnect by the same owner replaces its observer, transfers pending setup once,
and fences the old connection's input and replies.
The MCP client makes short reads for up to about twenty seconds; a returned `QUEUED` or `RUNNING` task
can be waited on again by ID. Neither recovery path submits the parent prompt
again.

Children can propose a typed summary and Bosca metadata artifact references with
`submit`. A terminal native receipt determines the final status; a proposal alone
does not mean completion. Failed, cancelled, timed-out and interrupted tasks can
retain partial output. Parent reads recheck current source and artifact access.
The final result is recorded once in the parent's canonical history and enqueues
its reconciliation. Parent cancellation reaches active descendants. Completed
children retire through normal archival; their archives follow the profile's
retention policy and are removed when the parent conversation is deleted. Parent
purge waits for descendant workloads and archives to be removed first.

## Runtime diagnostics

Runner emits JSON events through the existing SLF4J log configuration with
`event=kit.runtime`. Events correlate the Bosca trace ID, conversation, durable turn,
generation, ownership epoch, Kubernetes dispatch, initiating principal, approval,
configuration identity and policy revision. The actor distinguishes Runner work
from a principal's approval decision. Context events retain instruction/context
revisions and the permitted source identities and versions, including memory reads.
MCP events identify the configured integration and whether its readiness probe
succeeded. Exception events include the exception class without its message or stack.

The event payload selects these fields explicitly. Prompt and response bodies,
tool requests/results, approval payloads/options, endpoint URLs, environment,
credential values and full configuration snapshots are excluded. Runtime identities
and context revisions are event fields; they are never counter dimensions.

Minute buckets use the platform's distributed `Counter`. The existing Analytics
counter API reads the `kit.runtime.*` family with its ordinary bounded windows.
For each operation and outcome, counters are named:

| Counter suffix | Value |
| --- | --- |
| `count` | Number of observations |
| `milliseconds` / `milliseconds_samples` | Sum of measured durations / number of duration samples |
| `bytes` / `bytes_samples` | Sum of measured sizes / number of size samples |
| `age_milliseconds` / `age_milliseconds_samples` | Sum of checkpoint ages / number of age samples |

For example, `kit.runtime.turn.success.count` counts successful terminal turns;
`kit.runtime.turn.success.milliseconds` divided by
`kit.runtime.turn.success.milliseconds_samples` gives their mean measured duration.
The storage key appends the epoch-minute; API callers use the prefix without that
bucket. Metrics have no conversation, principal, integration or configuration labels.

Startup success measures elapsed time from the durable dispatch timestamp. Terminal
turn latency starts at its persisted submission deadline minus `promptSeconds`,
so queueing/setup is excluded and approval waits and Runner outages are included.
Terminal redelivery does not emit another terminal measurement. Approval creation
and accepted/expired decisions have their own observations; decision duration uses
the persisted approval creation time. Approval cancellation performed as part of
closing a turn is represented by that turn's cancellation outcome.

Restore, archive, delete, reconciliation and credential issue/revoke metrics describe
attempts. A queue delay is `pending`, not a failed attempt. A verified checkpoint
commit emits `checkpoint.success` with its size; failures before commitment emit
`checkpoint.failure`. `archive.failure` can also represent a removal/revocation
failure after a checkpoint has already committed. Cleanup success measures elapsed
time from cancellation intent, checkpoint commitment or the deletion tombstone,
as applicable. Restores report checkpoint size and age since its verified commitment.

The recovery sweep logs `event=kit.runtime.snapshot` with global active and failed
totals. It also increments `kit.runtime.snapshot.samples`, `.active` and `.failed`.
Divide each accumulated total by `samples` for the mean across observations. These
are sampled global totals, including snapshots from multiple Runners, rather than
increment/decrement gauges. Active totals include dispatched workloads awaiting
removal; failed totals include visible admission failures and exclude tombstones.
Recovery events record bounded republishing attempts and their outcomes.

Counter writes are best-effort with a one-second budget per observation. An outage
logs a generic warning; it does not change an operation's result or suppress its
exception. Parent cancellation propagates, and cancellation events perform no
counter I/O. Counter retention follows the selected platform backend. Partial or
lost observations are possible during failures, so these metrics are operational
estimates; PostgreSQL turn/approval outcomes remain the durable audit.

Apply the registered timestamp migration with matching API/Runner artifacts.
Existing checkpoints and approvals without reliable creation times retain unknown
ages/durations; those measurements are omitted rather than assigned invented dates.

## Selected working set

Studio's context panel can select content items, collections, WorkOps tasks,
specifications and requirements, and individual Git files. WorkOps selections
use project browsing with offset pages; specifications and requirements show their
permitted document names. Git selection uses the existing file picker. Selection
does not start a runtime or extend its idle deadline, and changes apply to the next
message. Individual sources can be removed; **Clear WorkOps and Git selections**
also removes unavailable references whose labels are no longer visible.

The existing chat ownership and `ai:execute` boundary protects the additive
`addHarnessSource`, `removeHarnessSource` and `clearHarnessSources` mutations.
Sources are typed identities, with a repository-relative path and requested ref
for Git files. A conversation stores at most 16 additional selections. Its
`harnessContext.selectionCount` includes unavailable selections, without exposing
their names or bodies. Existing content/collection attachments retain their normal
selection actions. Managed defaults come from the Agent's existing configuration:

```json
{
  "harnessContext": {
    "metadataIds": ["<content-uuid>"],
    "collectionIds": [],
    "memoryIds": [],
    "sources": [
      { "kind": "TASK", "id": "<task-uuid>" },
      { "kind": "GIT_FILE", "id": "<repository-uuid>", "ref": "main", "path": "docs/plan.md" }
    ]
  }
}
```

`sources` also supports `SPEC` and `REQUIREMENT`. Explicit conversation choices
precede managed defaults and duplicate identities are considered once. The first
16 content/domain candidates share the existing context character budget with
instructions, bounded memories and reconstructed conversation. Excerpts are
limited to 4,000 characters per source; omissions and truncation are reported in
the context status. Collections remain references rather than expanded catalogs.

WorkOps uses its owning services and shared task/specification/requirement
permission evaluators. A task contributes its independent Markdown description.
Linked rich documents are read through Content only after its metadata and body
permissions pass; WorkOps visibility alone does not reveal a private document.
No linked task, requirement, project or repository is automatically expanded.

Git uses `RepositoryService`, `RepositoryBrowseService` and its existing access
evaluator. A deployment profile must explicitly permit `git:read` for private Git
context, intersected with the caller's scopes and groups. Repository ACLs still
apply. The requested ref resolves to a commit before reading the selected path,
so a branch movement cannot mix file revisions within that read. The manifest
retains the requested ref/path, resolved commit and content revision. Binary and
oversized blobs are references only. These reads do not clone repositories or
copy a repository catalog into the harness filesystem.

WorkOps row versions and linked document content hashes detect edits, including
edits at the same metadata version. Git commit/blob identities detect changed
refs and files. A changed working set follows the existing context retirement
path and supplies a new native session with current, permitted sources. Completed
turn manifests stay immutable. Authorized changes can keep saved workspace files;
lost source or linked-body access prevents restoring derived workspace/native
state. The last committed archive remains available through the existing lifecycle.
Missing optional modules or unavailable records produce generic omissions; read
failures and cancellation retain their ordinary failure behavior.

Deploy the updated API/Runner and GraphQL schema before Studio. The shared WorkOps
evaluators live in `core-workops` with their existing package, constructors and
permission behavior. Kit consumes core contracts, and resolves domain providers
only when those source kinds are used. This increment adds no database migration.

## Model and reasoning choices

The private `kit.harness` Configuration can add a `models` entry for each allowed
profile. For example, replace the placeholder with an administrator-approved model
ID advertised by the deployed harness:

```json
{
  "defaultProfile": "kit-codex",
  "allowedProfiles": ["kit-codex"],
  "models": {
    "kit-codex": {
      "defaults": { "model": "<approved-model-id>", "reasoning": "medium" },
      "allowedModels": ["<approved-model-id>"],
      "allowedReasoning": ["medium", "high"],
      "agentDefaults": { "kit": { "reasoning": "medium" } }
    }
  }
}
```

The allowlists are the administrator's approval boundary for provider access and
cost. Harness discovery does not prove account entitlement or remaining budget.
The provider still enforces its account limits. There is no automatic entitlement
or budget service in this path.

Each configurable control requires an explicit approved managed default. Resolution
is per control: authorized chat choice, Agent-key default, system default, then the
harness default when the control is unmanaged. Omitting `models` leaves model
controls managed by the harness and exposes no overrides. Configuration EXECUTE
permission gates model and reasoning choices just as it gates profile choices.

The Codex adapter reads its advertised ACP session configuration selectors and
returns normalized model and reasoning values. Studio receives only policy-approved
advertised choices. Model IDs and display names come from that session, without an
embedded model catalog. Reasoning effort is separate from response length or visible
reasoning summaries. Other native controls, including approval modes and service
tiers, are not exposed through these settings.

Runner applies choices before a prompt, changes the model before reasoning, then
checks the updated reasoning options and confirmed values. The supervisor rejects
settings changes during active work and updates its warm setup cache from confirmed
responses and configuration notifications. A running turn retains its requested and
effective values, per-control source, policy revision and capability revision in its
durable settings snapshot. The capability revision includes the selected model and
its advertised choices, so effort changes alone do not invalidate the selector.

Saved overrides are rechecked against current policy and capabilities before each
turn and after restore. Revocation uses managed defaults visibly. Unsupported
settings stop before a prompt is submitted, preserve queued work and report a
settings failure; repair the choice or policy and use Retry session. A model change
keeps the warm native session. Profile or model-policy changes drain the generation
and restore only workspace files into a fresh native session. Native checkpoints
are not carried between profiles.

GraphQL rejects unauthorized, unapproved or stale-capability choices. Switch a
profile first; its model controls become available after activation. A model change
clears the reasoning override. Studio enables its newly advertised reasoning choices
after that model has been applied on the next turn.

## Approved MCP connections

Runner resolves external connections through `McpServerRegistrationService` and the
selected Agent's assigned tools. A registration must be enabled and referenced by
an assigned tool's `mcpServerId`. Registry permissions remain unchanged. Conversation
settings expose names, selection keys, required flags and readiness notices.

By default every assigned registration is required. An Agent can select a subset
and mark individual connections optional in its existing configuration:

```json
{
  "harnessMcp": [
    { "key": "documents", "required": true },
    { "key": "calendar", "required": false }
  ]
}
```

Callers with the private policy's `EXECUTE` permission can select optional entries
for the next turn in Studio. They cannot add unassigned registrations or remove
required entries. Bosca's standard local MCP connection is always present.

Each registration supplies a typed `harness` section in its existing configuration.
For a `STREAMABLE_HTTP` registration:

```json
{
  "harness": {
    "url": "https://tools.example.com/mcp",
    "headers": {
      "Authorization": { "environment": "DOCUMENTS_ACCESS", "prefix": "Bearer " }
    }
  }
}
```

Mount `DOCUMENTS_ACCESS` from a Kubernetes Secret in the runtime JobProfile and
include its name in the deployment profile's `mcpSecretEnvironment`. Values stay in
the runtime. References cannot name `BOSCA_*` variables; provider and control
credentials are configured independently. Endpoints do not contain user info,
query parameters or fragments. Authentication uses header references. This path
uses supplied credentials; it does not initiate interactive OAuth or refresh an
external account. Configure any required hosted credential lifecycle separately.

For a `STDIO` registration, use a deployment-approved executable key:

```json
{
  "harness": {
    "executable": "documents",
    "environment": { "API_TOKEN": { "environment": "DOCUMENTS_ACCESS" } }
  }
}
```

The corresponding deployment profile includes:

```yaml
mcpSecretEnvironment: [DOCUMENTS_ACCESS]
mcpExecutables:
  documents: ["/opt/integrations/documents", "--stdio"]
```

Package that executable and its dependencies in the runtime image. Commands and
arguments come from deployment configuration; do not put credentials in them.
`bosca harness mcp <key>` reads only its prepared connection from `/state/control`
and starts the executable with its selected environment. Runner does not resolve
secret values or place them in ACP parameters. The supervisor supplies HTTP
headers immediately before forwarding session setup to the native adapter.

Readiness checks the harness's advertised transport capabilities, resolves mounted
credentials, and performs MCP initialization and tool discovery with the pinned
Kotlin SDK. Required failures stop readiness and preserve queued messages.
Optional failures produce a visible notice and exclude the connection for that
generation. Probe failures do not expose upstream response bodies or credentials.
A native tool call can still fail after readiness; the probe is not a continuing
availability guarantee. No tool is executed by the probe.

Warm reconnects retain the same prepared connections. Registry, assignment or
selection changes drain the runtime between turns, retire its Bosca token, and
start a new native session with the retained workspace. Startup rechecks policy
before restoring state. Every restored generation resolves current mounted
credentials and probes again. Use **Archive session** while idle, then **Resume session** after repairing an optional
connection or rotating a mounted credential. **Retry session** resumes a repaired failed
session and its queued messages without requiring another user message. Known credential values are redacted
from streamed output and receipts; archival refuses retained files containing
those values instead of modifying opaque native state.

## Infrastructure boundary

`KitRuntimeProvider` supplies workload creation, observed identity/connectivity,
terminal reconciliation and removal to the shared lifecycle. The Kubernetes
implementation uses `KubernetesJobDispatchService`; only the separate controller
materializes resources and decrypts the generation environment. Controller-native
requests, resource names and endpoint-template expansion stay in that adapter.
Conversation persistence retains an opaque dispatch reference. This boundary does
not add a provider registry or change unrelated Kubernetes jobs.

## Checks

From the workspace root:

```sh
./gradlew :cli:test --tests 'bosca.cli.harness.*'
./gradlew :cli:nativeCompile
node ai/kit/runtime/tests/check_cli.mjs cli/build/native/nativeCompile/bosca
node ai/kit/runtime/tests/check_cli.mjs cli/build/native/nativeCompile/bosca claude
./gradlew :ai:kit:test
./gradlew :kubernetes:kubernetes-controller:test --tests 'bosca.kubernetes.controller.jobs.*'
```

To exercise a compiled native CLI, set `KIT_RUNTIME_CLI` to its absolute path
when running the lifecycle test. Node is required for the local subprocess fixtures.

The JVM lifecycle test runs both Codex and Claude compatibility manifests and native file layouts through real generated PostgreSQL repositories, the real HTTP supervisor and a synthetic native subprocess. It covers warm turns, unused-session restore, upload failure, corrupt storage reads, credential revocation failure, failed or delayed workload removal, queued reactivation, startup expiry across retries and stalled ACP setup, Runner cancellation during startup, canonical context boundaries, permission denial, policy changes during approval, policy and membership revocation before delivering a recovered decision, Runner reconnection during approval, cancellation during startup/inference/approval, unconfirmed cancellation, recovery, uncertain outcomes and retried deletion. Cleanup retries keep the committed archive and do not export it again; corrupt restore input leaves queued turns unsent. Runtime HTTP tests also hold a cancellation request after admission, transfer ownership, and verify the delayed request is rejected before reaching the native process. Client HTTP tests verify cancellation during a blocked control request and archive-body download.

Journal tests cover both byte and turn limits, retained receipts across ownership
changes, duplicate rejection, failed writes, and oversized native lines without a newline. The
database lifecycle test verifies retirement at each limit with queued messages,
then disconnects Runner during a journal overflow and checks interruption,
checkpoint retention and recovery without replay.

Phase-budget checks stall restore and native initialization, retry the original
generation, and keep prompts queued until setup succeeds. They also verify that a
running prompt outlasts its readiness budget, and that permission expiry works
both live and after Runner replacement. HTTP tests cover stalled headers and
bodies, independent MCP/restore budgets, and parent cancellation.

Turn-budget checks cover queue time, native cancellation, approval expiry after
Runner replacement, unconfirmed cancellation and preserved queued work. Runtime
lifetime checks expire during startup, active inference and a Runner outage,
then retry revocation and delayed workload removal. Supervisor checks also block
native stdin to verify that backpressure cannot prevent deadline shutdown.

Installed adapter negotiation can be checked without opening a provider session.
In a temporary installation, copy the selected package/lock/compatibility files,
run `npm ci --omit=dev --no-audit --no-fund`, then run from the workspace root:

```sh
node ai/kit/runtime/tests/check_adapter.mjs /absolute/path/to/installation
```

The probe clears inherited credentials and uses empty native home directories. It
checks installed package identity and advertised ACP initialization only.
Linux image and live-provider qualification remain separate deployment checks.

Admission checks race independent database transactions across principals and
profiles, verify rollback releases reservations, and retain capacity during live
workload deletion. The lifecycle test verifies rejection before credentials or
dispatch, preserved queued turns and checkpoints, Retry session after capacity
becomes available, and capacity release while archive deletion is retried.

### Analytics warehouse access

Harnesses can use `analytics_sql` for warehouse discovery and read-only investigations.
Profiles granting it need `analytics:sql`, and the effective principal must remain an
administrator or analytics manager after credential group restrictions. Saved-query
execution uses its existing `analytics:execute` scope and entity permissions. Inline
results carry executed SQL, typed columns, timing and truncation; Studio renders their
selected chart and evidence. Saving query/visualization/dashboard artifacts uses the
existing analytics MCP tools and their separate permissions.
