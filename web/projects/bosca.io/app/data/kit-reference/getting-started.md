# Getting Started with Kit

Kit connects a Bosca conversation to a native coding harness, such as Codex or Claude, running in an isolated workload. You interact through Studio or a protocol client. The harness does the model work and chooses tools. Bosca supplies permitted context and tools, records the conversation, and manages the workload's lifetime.

These pages explain the current workspace implementation. Use matching API, Runner, CLI, runtime images, and Studio builds. Check the schema and command help in an installed release rather than assuming it contains every feature described here. Runtime images and provider access need qualification in the environment where you deploy them.

## Choose a starting point

| You want to… | Start here |
| --- | --- |
| Understand the pieces and where state lives | [How Kit Works](/kit-reference/concepts) |
| Work with conversations, context, tools, and memories | [Working with Kit](/kit-reference/using-kit) |
| Decide which harnesses and tools people can use | [Configuration](/kit-reference/configuration) |
| Run conversation workloads on a cluster | [Kubernetes](/kit-reference/kubernetes) |
| Run them through Docker's manager API | [Docker Swarm](/kit-reference/swarm) |
| Understand the role of a Compose installation | [Docker Compose](/kit-reference/compose) |
| Monitor, repair, back up, or upgrade an installation | [Operations and Support](/kit-reference/operations) |
| Add instructions, integrations, or a new capability | [Extension Points](/kit-reference/extending) |

For the platform itself, begin with [Run Bosca Locally](/developers/run-locally) and the [Developer Documentation](/developers/getting-started). Starting Bosca's applications is a separate step from configuring Kit's conversation workloads.

## Follow one conversation

1. You create a Kit conversation in Studio and send a message. Bosca records an ordered turn under your identity.
2. A background Runner resolves the current policy, instructions, permitted sources, and tool connections. It admits a runtime generation within the installation's capacity limits.
3. The infrastructure controller creates a Kubernetes Job or Swarm Service. The runtime image contains the native Bosca CLI supervisor, an ACP adapter, and the selected harness.
4. The Runner checks the image and runtime identity, prepares the MCP connections, opens the native session, and submits the turn. The harness calls its model provider and tools. Studio receives text, plans, tool updates, and supported results.
5. A follow-up normally uses the same native process and its existing context. After the configured idle timeout, Bosca stops the native writers, archives the harness's operational directory and workspace, verifies the stored archive, and removes the workload.
6. Returning to the conversation creates a new generation with current credentials. Bosca checks whether the saved context is still permitted, restores eligible files, and uses native resume or load when supported. It does not repeat old prompts to rebuild the session.

A conversation can outlive many workload generations. A stopped workload does not mean the conversation was deleted.

## What you can use Kit for

The tool integration supports work on Bosca content, images, scripts, pipelines, analytics, WorkOps records, Git context, scripture, and saved memories. For example, you can ask Kit to draft a document, investigate analytics data, prepare a script change, or work from selected project requirements. Tools can save real platform records; an answer in chat is not itself proof that a record was saved or published.

Availability depends on the deployed modules, configured tools and providers, runtime scope ceiling, your permissions, and the harness's capabilities. Image generation, for example, uses Bosca's configured image service; the presence of a coding model does not configure that service. Script and pipeline operations have their own access requirements.

Use concrete requests and inspect the returned records. A useful first exercise is to select a document you can read, ask for a summary with its source, then ask for a separate draft. Check the saved content link before taking a publishing action. See [Working with Kit](/kit-reference/using-kit) for controls and access boundaries.

## What an operator must provide

For a standard installation, enable Kit, choose Codex or Claude, and supply the provider credential through the normal Helm Secret or encrypted Swarm configuration. Use your normal platform deployment command. The templates provide the runtime controller/workload settings, matching application profiles, image identities, and component versions. The platform's existing database, jobs, Runner, and private object storage hold Kit's durable state.

Start with the [Helm deployment](/kit-reference/kubernetes#standard-helm-deployment) or [Swarm deployment](/kit-reference/swarm#standard-swarm-deployment). Resource limits, scope ceilings, timeouts, and retention have defaults with optional overrides. A private `kit.harness` policy is optional; add it when you need custom choices, Agent defaults, or delegation controls. Provider acceptance and routing checks are part of deploying the selected profile.

The managed providers are **Kubernetes** and **Swarm**. Compose can run platform components and supporting services, but there is no separate Compose provider that creates one harness container per conversation. The [Compose guide](/kit-reference/compose) explains the supported combinations.

## Read the continuity boundary correctly

Bosca keeps canonical conversation history, native harness checkpoints, and shared memories as different things. A checkpoint preserves the native operational directory after writers stop, with provider authentication supplied separately. A memory is an explicitly saved Bosca document. Upgrading the same harness preserves its directory and lets the harness reopen or migrate its own files, as on a local upgrade. Changing the harness family, instructions, or permitted sources can require a new native session.

An interrupted turn has an uncertain outcome. Bosca does not automatically replay it, and cancellation does not undo completed tool operations. Local changes made after the last committed archive can be lost with the workload. These boundaries matter when a request publishes content, sends a message, changes a repository, or starts another durable job.

Continue with [How Kit Works](/kit-reference/concepts), then use the deployment and operations guides for your installation.
