# Extending Kit

Choose the extension point that owns the change. Instructions, permitted data, tool integration, native harness packaging, and workload scheduling are separate responsibilities.

| Desired change | Extension point |
| --- | --- |
| Change Kit's guidance or default sources | Existing Agent and Prompt configuration. |
| Offer another approved model, reasoning choice, or profile | Deployment profiles and private `kit.harness` policy. |
| Connect an external tool service | MCP registration, Agent tool assignment, and deployment-approved credentials/executables. |
| Add an operation on a Bosca domain | Its service/API and the shared MCP tool contract. |
| Delegate a bounded subtask | Opt-in managed child policy and `bosca_delegate`. |
| Use another native harness | ACP capability integration, a qualified image, and an explicit native state policy. |
| Launch runtimes on another infrastructure | `KitRuntimeProvider` and its concrete lifecycle/identity/removal contract. |

## Instructions and default context

The Runner resolves the conversation's Agent and Prompt before a turn. With no `kit` Agent, Kit has built-in guidance. An Agent that references a missing Prompt fails preparation and leaves unsent work available for repair.

Configure managed source defaults on the Agent:

```json
{
  "harnessContext": {
    "metadataIds": [],
    "collectionIds": [],
    "memoryIds": [],
    "sources": [
      { "kind": "GIT_FILE", "id": "REPOSITORY_UUID", "ref": "main", "path": "docs/guide.md" }
    ]
  }
}
```

`sources` also supports `TASK`, `SPEC`, and `REQUIREMENT` identities. References are checked through their owning services, current groups/scopes, and body permissions. Conversation choices precede defaults. This configuration selects context; it does not grant access, clone repositories, or mount source files into the workspace.

Configured system/user guidance is supplied as ACP content; the integration does not assume an ACP system-message field. Retrieved source material is identified as reference data. Keep instructions within the context budget and separate operational policy from instructions: a prompt does not replace API authorization.

## Approved external MCP connections

Register and enable an MCP server in Bosca, assign a tool referencing its `mcpServerId` to the Agent, and configure the registration's typed `harness` section. Assigned registrations are required by default. An Agent can select a subset and mark optional connections:

```json
{
  "harnessMcp": [
    { "key": "documents", "required": true },
    { "key": "calendar", "required": false }
  ]
}
```

Authorized conversation overrides can select optional assigned entries. They cannot add unassigned registrations or remove required ones. The standard Bosca MCP connection is always present.

### HTTP example

For a `STREAMABLE_HTTP` registration, configure a URL and a credential reference:

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

Mount `DOCUMENTS_ACCESS` into the runtime through its Kubernetes or Swarm profile, and list the name in deployment `mcpSecretEnvironment`. Credential values stay in the runtime. Endpoints exclude user information, query parameters, and fragments; authentication uses header references. References cannot select `BOSCA_*` environment fields.

This path uses supplied credentials. It does not run an interactive OAuth flow or refresh an external account. Any hosted credential lifecycle belongs to the integration's deployment process.

### Standard-input/output example

For a `STDIO` registration, name an approved executable and its selected environment:

```json
{
  "harness": {
    "executable": "documents",
    "environment": { "API_TOKEN": { "environment": "DOCUMENTS_ACCESS" } }
  }
}
```

The deployment profile supplies the actual command:

```yaml
mcpSecretEnvironment: [DOCUMENTS_ACCESS]
mcpExecutables:
  documents: ["/opt/integrations/documents", "--stdio"]
```

Package the executable and dependencies in the qualified runtime image. Commands and arguments come from deployment configuration; secret values use mounted references. The native launcher uses `bosca harness mcp <key>` to read its prepared connection and start the approved program.

Readiness verifies transport capability, resolves mounted credentials, and performs MCP initialization and tool discovery. It does not execute a tool. Required failures block readiness; optional failures produce a notice and omit that connection for the generation. Warm reconnects retain prepared connections; changes drain through the ordinary lifecycle and fresh generations probe again.

## Add a Bosca capability

Put domain behavior in the owning service and expose it through its normal API, with boundary authorization and existing permissions. A code-backed MCP tool implements `IAgentTool`, executes through `GraphQLService` under the caller's authentication, has a named provider matching its tool key, and needs a corresponding code-backed seed. Simple operations can use the existing GraphQL-backed tool mechanism. New installer seeds require the owning package and installer version changes.

Return typed MCP content and stable Bosca resource links for actual saved records. Keep raw arguments, private reasoning, and credentials out of display content. Adding a new resource kind can also require a deliberate Studio renderer; current rendering does not fetch arbitrary resource URIs.

Test allowed and denied caller scopes, entity access, cancellation, partial failures, and any side effects. Keep durable execution in the owning jobs/services. A tool timeout is not permission to replay an uncertain mutation. Follow the [developer architecture](/developers/architecture), [services](/developers/services), and [GraphQL](/developers/graphql) guides.

## Use managed child tasks

Enable an approved delegation policy and `ai:delegate` scope ceiling. Use a stable request UUID for `bosca_delegate start`, with a brief, expected result, approved profile, and permitted selected sources. Reusing that UUID with identical input returns the same task; changed input fails.

A child has a separate session/workspace and narrowed authority. Share selected context, saved memories, and artifact references rather than assuming a shared checkout or inherited native conversation. A child's `submit` proposes structured output; its terminal receipt confirms the outcome. See [Working with Kit](/kit-reference/using-kit#managed-child-tasks).

## Add another native harness

Supporting ACP is the conversation prerequisite, not the whole integration. The current supervisor descriptor explicitly accepts the qualified Codex and Claude state policies. Replacing an image or changing a policy string does not add another family.

A new harness integration needs:

1. A packaged, pinned adapter/harness installation with unattended provider authentication.
2. Verified initialization, session creation, advertised resume/load, tool transports, updates, permission requests, cancellation, and model-setting behavior.
3. An explicit native-file selection policy, credential/cache exclusions, persistent scratch handling, fixed paths, and writer-quiescence behavior.
4. Manifest and installed-version verification in the supervisor, plus compatible profile identity pins.
5. Native-image and container tests, a real provider-backed cold-return exercise, and tests for interrupted turns, unsupported files, archive corruption, permissions, and cleanup.

Keep files opaque after writers stop; do not assume another harness has the same transcript/database layout or that a storage callback proves checkpoint commitment. If native resume/load is unavailable, reconstruction needs current permitted canonical context and a visible continuity boundary. It must not repeat earlier prompts or side effects to reconstruct state.

## Add another workload provider

`KitRuntimeProvider` separates infrastructure from the shared lifecycle. The current implementations use `KubernetesJobDispatchService` and the Swarm runtime controller. Another implementation must provide idempotent creation, observed image/connectivity identity, terminal reconciliation, and confirmed removal.

It must preserve admission ownership, immutable dispatch identity, generation credential delivery, process-instance fencing, and cleanup after uncertain responses or controller replacement. Scheduling logic belongs in that infrastructure adapter/controller; conversation history, approvals, context, and archive commitment stay with the shared lifecycle.

There is no generic provider plug-in registry or Compose implementation to select today. Adding a provider is platform work with composition, configuration, persistence, and real infrastructure tests. It is not accomplished by pointing `endpointTemplate` at an unrelated long-lived process.
