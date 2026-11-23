# Operating and Supporting Kit

Operate Kit as a durable application with temporary execution workloads. PostgreSQL records accepted work and outcomes. Object storage holds committed checkpoints. A warm container holds changes that may not yet exist in either place.

## Establish a baseline

For each enabled profile, record its infrastructure, qualified image reference/identity, component versions, provider account, controller, runtime endpoint pattern, scope ceiling, resource limits, timeouts, and archive policy. Keep API and Runner configuration matched.

Verify these paths from their actual callers:

- API and Runner to the configured runtime controller.
- Runner to the workload's control endpoint and ACP WebSocket.
- Workload to the Bosca API, provider, and required MCP integrations.
- Runner to shared private object storage for upload, read-back verification, and restore.

An HTTP-ready supervisor has started its server. Full native-session readiness also needs correct image identity, provider/adapter setup, selected integrations, and any restore. Check the conversation's lifecycle and failure notice rather than treating a container health check as a successful model turn.

Before opening a profile to users, run a synthetic-data acceptance exercise: create a conversation, complete two warm turns, save a workspace file, archive, confirm workload removal, resume in a new generation, complete another turn, cancel active work, and delete the conversation. Verify credential revocation, object cleanup, and infrastructure cleanup. Add real provider/tool operations appropriate to the profile's intended use.

## Capacity and retention

Kit's global and per-principal admission limits count dispatched generations across profiles, including waiting, warm, approval-waiting, and removing workloads. JobProfile or Swarm placement limits can impose another capacity boundary. At admission capacity, the conversation reports a failure and retains queued work; it does not continuously mint more credentials. Retry after capacity is available.

Plan for:

- Native process RAM and CPU, workspace/scratch growth, and archive staging.
- Idle and approval waits that retain an allocated workload.
- Archive space for both an old checkpoint and a replacement during commitment.
- Objects reserved for uploads or awaiting confirmed deletion, which still count toward quota.
- Current provider account limits; runtime seconds and model allowlists are not a spending cap.

The default cold archive retention has no age expiry. `maxArchiveAgeSeconds` can deliberately expire cold native state and workspace. Expiry leaves canonical conversation and independently saved memories under their own retention rules. Deleting a conversation starts workload/archive purge; failures remain eligible for recovery until cleanup succeeds.

Kubernetes Kit profiles should use zero finished-Job TTL for prompt failed-generation cleanup. A longer TTL retains Jobs and owned Services/Secrets. Swarm removal waits for task shutdown before deleting the Service and owned generation Secret.

## Observe lifecycle events

Runner logs structured JSON with `event=kit.runtime`. Correlation fields include trace, conversation, durable turn, generation, ownership epoch, dispatch, principal, approval, and policy/context revisions. Use them to follow one operation across API, Runner, controller, and workload.

The structured event payload excludes prompt/response bodies, tool arguments/results, endpoint URLs, credential values, approval payloads, and full configuration. That is the event's contract, not a guarantee about arbitrary provider output or every container log. Protect logs and support bundles according to the data users can put in a conversation or workspace.

The `kit.runtime.*` Analytics counters provide counts, measured durations, bytes, and checkpoint ages in bounded minute windows. For example, divide `kit.runtime.turn.success.milliseconds` by `kit.runtime.turn.success.milliseconds_samples` for mean measured successful-turn duration. Queue/setup time is excluded from that turn measurement; approval waits and Runner outages after submission are included.

`kit.runtime.snapshot` reports sampled active and failed totals. The counters are best-effort observations, not exact gauges, billing records, or a substitute for PostgreSQL's durable turn/approval history. An archive failure can occur after checkpoint commitment if revocation or workload removal fails; inspect the checkpoint and cleanup stages separately.

## A support workflow

1. Identify the conversation, durable turn, generation, infrastructure dispatch, and reported terminal/lifecycle state. Confirm the affected principal and profile without requesting raw credentials.
2. Determine whether the prompt was unsent, durably submitted, waiting for permission, completed, cancelled, or interrupted. A client timeout alone does not establish any of those outcomes.
3. Follow correlated Runner events and controller observations. Inspect the workload identity, scheduling events, and selected integration notices.
4. Check the current context/policy revisions and the last committed archive. Separate a successful checkpoint from a later cleanup failure.
5. Repair the owning configuration, capacity, credential delivery, network path, or storage service. Use the supported conversation controls to retry or resume.
6. Verify both the durable outcome and resource cleanup. Before requesting an uncertain side effect again, inspect the affected domain record or external system.

Kubernetes read-only diagnostics might include:

```sh
kubectl --context "$KIT_CLUSTER" -n kit get jobs,pods,services
kubectl --context "$KIT_CLUSTER" -n kit describe job "$KIT_JOB"
kubectl --context "$KIT_CLUSTER" -n kit get events --sort-by=.metadata.creationTimestamp
```

Choose the intended cluster and actual namespace first. For Swarm, use the intended Docker context and inspect the conversation Service's task state with `docker service ps`. Preserve evidence before removing resources. Avoid dumping Secret values or full application configuration into a support ticket.

## Common symptoms

| Symptom | Check | Recovery |
| --- | --- | --- |
| Startup stays queued or a runtime slot is unavailable | Kit admission totals, profile parallelism, node resources/quotas, controller health, and private queue dispatch. | Make capacity or repair dispatch, then use **Retry session**. Existing queued messages can proceed. |
| Runner cannot connect although the workload is running | The runtime endpoint from the Runner's network, DNS, Service/overlay routing, and WebSocket access. | Correct placement or private routing. A reachable Kubernetes API or Swarm manager is insufficient. |
| Image/identity mismatch | Bundled manifest/configuration digest or Swarm digest, CLI/adapter/harness/state-policy versions, and any custom image override. | Deploy the matched release metadata and image. Do not disable identity checks to reuse an unknown process. |
| Required MCP connection prevents readiness | Registration enabled state, Agent tool assignment, supported transport, approved executable, mounted secret names, and tool discovery. | Repair the required integration, then retry. Optional failures exclude that connection for the generation and show a notice. |
| Native permission wait or timeout | Offered request, recorded creation/deadline, current policy, and whether the turn is still active. | Answer a current request or let cancellation settle. A late decision does not extend the original deadline. |
| Archive fails or retirement is stuck | Source writers, unsupported filesystem entries, known credential values in retained files, quota, object storage, checksum/read-back, revocation, and removal. | Repair the failing stage and allow retry. The previous committed pointer is preserved; do not delete the live workspace as a repair shortcut. |
| Return starts a new native session or omits workspace | Changed profile/instructions/source revisions, memory changes, source access revocation, or configured archive expiry. | Read the continuity notice. Restore only currently permitted material; this can be the intended access boundary. |
| Turn becomes interrupted after workload loss | Runtime instance mismatch, node/task loss, missing receipt, or unconfirmed cancellation. | Inspect side effects; reactivate from the last committed checkpoint. Unarchived local changes can be lost. |
| Conversation deletion remains pending | Descendant workloads, controller availability, credential retirement, object-store deletes, and orphaned resources/TTL. | Repair cleanup dependencies and let recovery retry. Independently saved memories and published records have separate lifecycles. |

Repair through the owning services and supported APIs. Editing ownership, generation, turn status, or archive pointers directly in PostgreSQL can bypass the fences that recovery relies on.

## Credential rotation

User login, generation-scoped Bosca tokens, provider credentials, external MCP credentials, and the Swarm controller token have different lifecycles. Rotate each through its owning configuration/secret process.

Warm sessions keep prepared integration credentials. For a mounted provider/MCP credential change, arrange idle archival and a fresh generation, then verify readiness and a real call. Required probes check MCP initialization/tool discovery, not every later operation or provider entitlement.

Scoped Bosca tokens expire and are revoked; they are not refreshed. Normal generation retirement issues the next generation's token. Keep the configured credential lifetime and hard-runtime limits consistent. Known credential values are redacted from delivery/receipts, and archival refuses retained files containing them. User-authored files can still contain sensitive information outside those known values; archives require ordinary private-storage controls.

## Back up the durable boundary

Back up PostgreSQL, the private archive/object store, application configuration, relevant encryption keys, and separately managed provider/MCP/controller secrets. Include ordinary saved content and other domain storage in the platform's backup plan. Archives are private objects; apply the installation's object-storage encryption and backup policy.

A live `emptyDir` or tmpfs workspace is not a committed backup. To preserve current work, finish a turn and complete verified archival while the old runtime is available. Back up database and objects as a recoverable set and test restoration. Restoring a database cannot roll back operations already performed in an external service.

After a platform restore, verify credentials, current permissions, archive access, and interrupted/queued work before reopening conversations. Ensure old runtime generations are not still active outside the restored installation. Use an isolated restore exercise rather than relying only on successful backup-job status.

## Upgrade and qualify

1. Coordinate a period without new submissions. Finish accepted work or explicitly cancel and reconcile it.
2. Archive idle sessions with the currently deployed compatible runtime, then confirm removal and save a recoverable backup.
3. Apply the normal Helm or Swarm release upgrade, which deploys matching components and runtime metadata. Custom installations must keep API, Runner, controller, native CLI/runtime, and Studio matched too.
4. Same-family image upgrades reopen the archived operational directory and retain the native session. Native files remain opaque; the harness owns any data migration, as it does locally. Keep a recoverable pre-upgrade backup, especially before a possible downgrade. Switching harness families uses a new native session rather than sharing native databases.
5. Resume an isolated conversation and verify real provider authentication, permitted tools, warm reuse, cold return, cancellation, and deletion.

The release image gate checks the installed native supervisor with synthetic ACP/MCP behavior, including standard and Swarm layouts. It does not certify a target environment, provider account, native feature, or cross-version archive. Use the steps above as acceptance tests; a green transport test alone is not evidence of a useful provider-backed outcome.
