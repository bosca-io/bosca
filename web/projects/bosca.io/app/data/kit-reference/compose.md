# Kit and Docker Compose

Compose starts a declared set of containers. Managed Kit conversations need a controller that can create, observe, and remove a workload for each generation. The implemented providers for that responsibility are Kubernetes and Swarm; there is no `COMPOSE` infrastructure value.

This distinction matters when a local Bosca installation has a working Studio chat screen but no configured runtime plane. Running the API and Runner does not itself provide a harness image, provider account, scheduling, or per-conversation credential delivery.

## Which Compose installation are you using?

| Installation | What it supplies | Kit consequence |
| --- | --- | --- |
| Workspace root `docker-compose.yaml` | An image-based Bosca application stack and backing services, described in [Run Bosca Locally](/developers/run-locally). | The checked-in baseline has Kubernetes disabled and does not configure per-conversation Kit runtimes. Install matched builds and explicitly connect a runtime plane. |
| `server/services/docker-compose.yaml` | Dependencies for applications running from source on the host. | Run API and Runner separately. Kit still needs its profiles, private policy, controller, and runtime network. |
| `deployments/bosca-compose/` | A portable, Git-focused installation with its own application configuration and storage. | It is not a Kit installer. Adding Kit requires the appropriate application modules/builds and a managed runtime configuration; its small-server resource budget does not include harness workloads. |

For the root stack, start with the platform guide and its environment setup:

```sh
docker compose config --quiet
docker compose up -d
```

These commands start that stack. They are not an additional Kit installation step or evidence that an installed published image contains the current harness integration.

## Compose platform, Kubernetes workloads

The API and Runner can be deployed independently from the runtime cluster, provided the actual network paths work. Supply matching deployment profiles and private policy, enable the Kubernetes module, register the cluster, and configure the separate controller.

The crucial path is **Runner to runtime Service**, including the ACP WebSocket and archive transfers. A host or Compose container generally cannot use a cluster's `.svc` name merely because it can call the Kubernetes API. Use a Runner placement or private routing arrangement that reaches the runtime origin, and set the endpoint template accordingly.

The workload must also reach `boscaEndpoint`, and Runner must reach shared object storage. `localhost` inside a container refers to that container. Choose addresses from the perspective of the caller rather than copying host-development ports into a profile. Follow [Kit on Kubernetes](/kit-reference/kubernetes) for the complete runtime requirements.

## Compose platform, Swarm workloads

A Linux Swarm manager and the Kit manager controller can provide the runtime plane for an otherwise Compose-managed installation. The controller needs manager socket access, its private bearer token, the matching encryption key, and approved Service profiles. The API/Runner need the controller URL/token and matching `SWARM` profiles.

Arrange a private path from Runner to dynamically created Services. A Compose project network by itself does not make Swarm service DNS reachable. Configure the shared networking or routing deliberately and verify it from the actual Runner container. Follow [Kit on Docker Swarm](/kit-reference/swarm).

Running `docker compose up` on a host that belongs to a Swarm does not turn its ordinary containers into managed conversation Services. Likewise, `bosca swarm deploy` renders site stacks; Kit's runtime controller and profiles remain a separate configuration step.

## Local runtime testing

The runtime images can be tested on a local Docker engine. The repository's image gate starts isolated containers with synthetic ACP/MCP fixtures and exercises the installed native CLI:

```sh
KIT_RUNTIME_IMAGE=YOUR_LOCAL_IMAGE \
KIT_RUNTIME_FAMILY=codex \
KIT_RUNTIME_VERSION=YOUR_CLI_VERSION \
./gradlew :ai:kit:runtimeImageTest
```

Run Gradle from the workspace root and use an image matching the engine architecture. For a Claude image, set `KIT_RUNTIME_FAMILY=claude`. This gate does not call a model provider or wire a standalone container into Studio's managed conversations.

`bosca harness serve` is the low-level supervisor entrypoint used inside those images. It expects a verified runtime installation, generation identity, control credential, expiry, and native command. Starting it alone is not a Compose scheduling provider or a shortcut around managed admission.

## Persistence and backups

Back up the platform database, object storage, relevant encryption keys, and separately managed configuration/credentials. A Compose named volume protects only the service mounted on it. It does not automatically capture an ephemeral harness workspace or replace the Runner's committed archive.

A harness workload's CPU, memory, writable storage, provider usage, and archive capacity are additional to the platform stack. Plan those independently and test the complete warm/archive/restore workflow before using the installation for important work. See [Operations and Support](/kit-reference/operations).
