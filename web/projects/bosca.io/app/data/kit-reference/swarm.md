# Kit on Docker Swarm

The Swarm provider uses the same Runner-owned conversation, context, approvals, scoped credentials, archive, and restore lifecycle as Kubernetes. Its infrastructure controller creates a single-replica Service for each generation and removes it after retirement.

The controller runs on a Linux Swarm manager and uses its Unix Docker socket. Runner and harness workloads do not receive that socket. The site installer can generate and deploy this controller, its provider Secret, and matching API/Runner profiles as part of the site's normal stack.

## Standard Swarm deployment

In the existing site's configuration, enable Kit and supply the provider credential through the configuration's existing secret/encryption process:

```json
{
  "kit": { "enabled": true, "harness": "codex" },
  "secrets": { "kitProvider": "YOUR_PROVIDER_API_KEY" }
}
```

Merge these fields into the site entry; preserve its other settings and secrets. Select `harness: claude` for Claude. Then run the normal commands:

```sh
bosca swarm render --config ./swarm.local.json
bosca swarm deploy --config ./swarm.local.json
```

The CLI resolves the runtime image to an immutable digest and reads its packaged component versions. It renders the controller, private routing, provider Secret, resource/isolation settings, and matching application profiles. The controller token is generated and preserved by the installer. Runtime images default to the installed CLI's release; `bosca swarm update-images` updates their configured releases alongside the platform images.

Private runtime images use the existing matching `registryAuth` login. Deployment mounts that credential in the manager controller as a separate Docker Secret; the controller supplies Docker's registry-auth header when it creates a runtime Service. The harness container does not receive the registry credential. Generated placement includes the pinned image's OS and architecture.

Kit runtimes have no direct internet access. Deployment creates the site's `<site>-kit-runtime` overlay with `--internal`, and a routable `<site>-kit-egress` overlay that carries only the site's `kit-egress` proxy. The proxy runs `bosca harness egress` from the same `bosca-cli` image as the controller, and the generated profile sets `egressProxy: http://kit-egress:3128` and the harness's provider host (`api.openai.com:443` or `api.anthropic.com:443`) in `egressHosts`. The proxy permits HTTPS `CONNECT` requests to the site's public hostnames, the profile's `egressHosts`, the remote MCP registrations assigned to the session's agent, and the [administrator's `egressHosts`](/kit-reference/configuration#outbound-destinations). For any destination other than a site hostname, it refuses a host that resolves to a private, loopback, link-local, shared, unique-local, multicast, or unspecified address. An existing runtime overlay that was created without `--internal` stops `bosca swarm deploy` with the commands to recreate it, because Docker cannot change that setting in place.

You do not start a second controller by hand or create a private Kit policy for this setup. Rendered files remain reviewable before deployment. The provider key is in the private bundle's Secret input file, not the workload's ServiceSpec environment. Use the existing encrypted configuration and backup process.

Optional overrides include `kit.image`, `kit.scopes`, `kit.idleSeconds`, admission limits, and container/state memory sizes. The sections below describe the generated controller contract and custom installations.

## Run a custom manager controller

Use the matching native Bosca CLI with curl, manager socket access, and a private controller configuration file. Supply through the deployment's secret process:

- `SECURITY_ENCRYPTION_KEY`, matching Bosca's encryption key.
- `BOSCA_SWARM_CONTROLLER_TOKEN`, at least 32 characters with no whitespace.
- A controller OS identity that can access the chosen Docker socket.

```sh
bosca swarm runtime-controller --config /etc/bosca/kit-swarm.json --port 8083
```

Configure both API and Runner with this application-configuration fragment:

```yaml
kit:
  swarm:
    controller:
      url: "http://kit-runtime-controller:8083"
      token: "$BOSCA_SWARM_CONTROLLER_TOKEN"
```

Keep the endpoint private. Runtime operations require its bearer token; `/healthz` returns only a status code. Manager configuration can override `socket`, `endpointTemplate`, `tokenEnvironment`, and `encryptionKeyEnvironment`. Treat access to this process as manager access, not as a user-facing chat endpoint.

For a custom controller using private runtime images, set the profile's `registryAuthSecret` to a filename mounted under `/run/secrets` in the controller. That file contains Docker's authentication JSON fields `username`, `password`, and `serveraddress`; the registry address must match the image's registry. This is separate from the provider Secret supplied to the harness.

## Configure the runtime Service profile

Create an approved private overlay network and provider Secrets through operations. Ensure the Runner can use the same runtime network and service DNS. The following manager-local JSON defines one Codex profile. Replace the image digest and make the referenced network and Secret available first:

```json
{
  "profiles": {
    "kit-codex-swarm": {
      "secretEnvironment": { "OPENAI_API_KEY": "kit-openai-key" },
      "service": {
        "Mode": { "Replicated": { "Replicas": 1 } },
        "EndpointSpec": { "Mode": "vip" },
        "TaskTemplate": {
          "ContainerSpec": {
            "Image": "ghcr.io/bosca-io/bosca/kit-codex-runtime@sha256:QUALIFIED_DIGEST",
            "User": "1000:1000",
            "ReadOnly": true,
            "Init": false,
            "Dir": "/state",
            "Args": ["--root", "/state/session", "--private-pid-namespace"],
            "StopGracePeriod": 30000000000,
            "CapabilityDrop": ["ALL"],
            "Privileges": { "NoNewPrivileges": true },
            "Env": [
              "HOME=/state/session/home",
              "TMPDIR=/state/session/scratch",
              "CODEX_HOME=/state/session/harness",
              "CLAUDE_CONFIG_DIR=/state/session/harness",
              "CLAUDE_CODE_TMPDIR=/state/session/scratch"
            ],
            "Mounts": [{
              "Type": "tmpfs",
              "Target": "/state",
              "TmpfsOptions": {
                "SizeBytes": 536870912,
                "Mode": 1023,
                "Options": [["exec"]]
              }
            }]
          },
          "Resources": {
            "Limits": {
              "NanoCPUs": 1000000000,
              "MemoryBytes": 2147483648,
              "Pids": 512
            }
          },
          "RestartPolicy": { "Condition": "none" },
          "Placement": { "Constraints": ["node.platform.os==linux"] },
          "Networks": [{ "Target": "kit-runtime" }]
        }
      }
    }
  }
}
```

`StopGracePeriod` is in nanoseconds; this example allows 30 seconds. `Mode: 1023` is the decimal representation of a sticky `01777` tmpfs root. The non-root supervisor creates its private `/state/session` directory with mode `0700`. The example tmpfs is 512 MiB and the container RAM limit is 2 GiB; tune them for the actual workload and leave RAM beyond tmpfs capacity.

The image supplies its own tini entrypoint. `Init: false` retains that arrangement. The controller validates the supported isolation settings: unprivileged identity, read-only root, dropped capabilities, no-new-privileges, bounded resources, no restart, and the approved state layout. Ports remain unpublished.

`workspace`, `harness`, and `scratch` under `/state/session` are archive roots. `control` and `home` are regenerated with current credentials. This profile creates no local volume records. Tmpfs is live state, not durable storage; worker loss can discard changes since the last committed archive.

## Match the Bosca deployment profile

Use the common limits, scope ceiling, component versions, and `boscaEndpoint` from [Configuration](/kit-reference/configuration), with these infrastructure fields:

```yaml
profile: kit-codex-swarm
infrastructure: SWARM
image: "ghcr.io/bosca-io/bosca/kit-codex-runtime@sha256:QUALIFIED_DIGEST"
imageId: "sha256:QUALIFIED_DIGEST"
endpointTemplate: "http://{service}:8080"
```

The profile name must match the manager JSON key. Swarm's observed identity is the pinned image digest in the running task's image reference; the supervisor separately verifies installed component versions. Ensure workers can obtain the exact image for their architecture.

For Claude, use the separate Claude image and state policy, `authenticationMethod: null`, and an `ANTHROPIC_API_KEY` Secret reference. Keep both home variables in the approved layout; each image uses its own. Provider credentials are independent of the scoped Bosca credential minted for each generation.

## Routing and secrets

Runner reaches the runtime's private service DNS on 8080. The runtime reaches the configured Bosca API, model provider, and selected MCP services. The controller's `endpointTemplate` can select another private origin where necessary. An address reachable from a manager is not automatically reachable from Runner.

Admission stores an immutable encrypted request in PostgreSQL. Reconciliation contacts the controller after commit; a rolled-back admission creates no workload. Deterministic names and a request hash let a repeated request recover the same generation after an uncertain response.

The controller decrypts generation fields into an owned Docker Secret. Provider and external MCP credentials reference separately managed Secrets. The supervisor reads credential files into memory; generated credential values are not ServiceSpec environment strings or archive entries. Mount/credential delivery still needs verification on the target engine and account.

## Removal and replacement

Cancellation intent is durable before the manager is contacted. The controller records cancellation in a short-lived Docker Config, scales the Service to zero, waits for shutdown, then removes the Service and owned Secret. It preserves provider Secrets. Unconfirmed removal retains cleanup intent and admission ownership for retry.

A five-second sweep resumes interrupted cleanup and removes expired owned runtimes. Cancellation records expire after the generation's hard deadline plus one minute. Confirmed removal clears the encrypted dispatch request.

Automatic native restart is disabled. If a task is unexpectedly replaced, the new supervisor identity causes reconciliation to interrupt uncertain work rather than replay it. The next activation can use queued messages and the last committed archive.

## Qualification

The source `:cli:swarmRuntimeTest` gate creates two isolated Docker daemons to exercise worker placement, credential delivery, isolation, controller replacement, task replacement, and cleanup. The `:ai:kit:runtimeImageTest` gate with `KIT_RUNTIME_LAYOUT=swarm` exercises the native image on this filesystem layout. Neither makes provider inference calls.

Before relying on a deployment, test the actual engine version, image architecture, private overlay routing, provider authentication, required MCP connections, cold restore, cancellation, and cleanup. A successful site-stack deployment or synthetic image check alone does not establish those results.
