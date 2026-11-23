# Kit runtimes on Docker Swarm

Kit selects infrastructure separately from the native harness. Set a deployment
profile's `infrastructure` to `SWARM` and use the same profile name in the manager
controller configuration. Omitted infrastructure selects Kubernetes.

The standard site installer does this automatically. Set a site's `kit.enabled`
to true, choose `kit.harness` (`codex` or `claude`), supply `secrets.kitProvider`,
and run the normal `bosca swarm render` / `bosca swarm deploy`. It resolves image
metadata and generates the controller, provider Secret, and matching API/Runner
profiles. It also creates the site's `<site>-kit-runtime` overlay with `--internal`,
so it has no route outside the Swarm. Runtimes join only that network; the site's
server and runner join it as well as `bosca`, so runtimes reach Bosca's API without
reaching Postgres, NATS, Dragonfly or other infrastructure. An existing runtime
overlay that is not internal stops `bosca swarm deploy` with the commands to recreate
it, because Docker cannot change that setting in place.

Runtimes have no direct internet access. The site stack adds a `kit-egress` service,
which runs `bosca harness egress` from the same `bosca-cli` image as the controller.
It joins the runtime overlay and a separate routable `<site>-kit-egress` overlay that
carries only the proxy, with the alias `kit-egress` on both. It does not join `bosca`.
The generated profile sets `egressProxy: http://kit-egress:3128` and `egressHosts` to
`api.openai.com:443` for Codex or `api.anthropic.com:443` for Claude. The runtime
supervisor gives the harness and the processes it starts proxy settings that carry
the session's own egress credential. The proxy supports only HTTPS `CONNECT` with that
credential and permits:

- the site's public hostnames on port 443, which are Bosca's own entry points;
- the provider hosts in the profile's `egressHosts`;
- the remote MCP registrations assigned to the session's agent;
- the `egressHosts` list in the private `kit.harness` policy, which administrators
  edit in Studio.

For any destination other than a site hostname, the proxy resolves the host and
refuses it if any resolved address is private, loopback, link-local, shared
(100.64.0.0/10), unique-local, multicast or unspecified. The proxy reaches the site's
public hostnames through their public addresses, so the egress overlay's outbound
route must reach the edge proxy's published ports.

The detailed instructions below describe custom controller installations.
The [Swarm deployment guide](../../../deployments/bosca-swarm/README.md#kit)
describes the standard path and optional overrides.

Private runtime images reuse the matching deployment registry login. The installer
mounts Docker authentication JSON in the manager controller through a Docker
Secret and the controller sends `X-Registry-Auth` when creating a runtime Service.
Registry credentials are not mounted in harness containers. Generated placement
includes the pinned image's OS and architecture.

The Runner owns admission, scoped credentials, context, ordered turns, approvals,
idle archival and restoration. The manager controller owns Swarm Services and
generation Secrets. Workers and Runner do not receive the Docker socket.

## Manager controller

Custom profiles can set `registryAuthSecret` to a manager-mounted filename under
`/run/secrets`. Its JSON contains Docker's `username`, `password`, and
`serveraddress` fields. The address must match the runtime image's registry.

Run the current native CLI on a Linux Swarm manager with curl and access to its
Unix Docker socket. The existing CLI container image includes curl. Supply the
same `SECURITY_ENCRYPTION_KEY` as Bosca and a private `BOSCA_SWARM_CONTROLLER_TOKEN`
of at least 32 characters. Configure socket access for the controller's OS user.

```sh
bosca swarm runtime-controller --config /etc/bosca/kit-swarm.json --port 8083
```

Both Bosca Server and Runner need:

```yaml
kit:
  swarm:
    controller:
      url: "http://kit-runtime-controller:8083"
      token: "$BOSCA_SWARM_CONTROLLER_TOKEN"
```

Keep the controller on the deployment's private network. Runtime operations
require bearer authentication; `/healthz` returns only a status code. Controller
configuration can override `socket`, `endpointTemplate`, `tokenEnvironment` and
`encryptionKeyEnvironment`.

## Runtime profile

The controller uses a native Docker `ServiceSpec` with supported isolation
controls. Create a private overlay network for runtimes and the provider Secrets
through the deployment's operations process. Attach only Bosca's API and Runner to
that network besides the runtimes; do not attach infrastructure services. Example
`kit-swarm.json`:

```json
{
  "profiles": {
    "kit-codex-swarm": {
      "secretEnvironment": {"OPENAI_API_KEY": "kit-openai-key"},
      "service": {
        "Mode": {"Replicated": {"Replicas": 1}},
        "EndpointSpec": {"Mode": "vip"},
        "TaskTemplate": {
          "ContainerSpec": {
            "Image": "ghcr.io/bosca-io/bosca/kit-codex-runtime@sha256:<qualified-digest>",
            "User": "1000:1000", "ReadOnly": true, "Init": false,
            "Dir": "/state",
            "Args": ["--root", "/state/session", "--private-pid-namespace"],
            "StopGracePeriod": 30000000000,
            "CapabilityDrop": ["ALL"],
            "Privileges": {"NoNewPrivileges": true},
            "Env": [
              "HOME=/state/session/home", "TMPDIR=/state/session/scratch",
              "CODEX_HOME=/state/session/harness",
              "CLAUDE_CONFIG_DIR=/state/session/harness",
              "CLAUDE_CODE_TMPDIR=/state/session/scratch"
            ],
            "Mounts": [{
              "Type": "tmpfs", "Target": "/state",
              "TmpfsOptions": {"SizeBytes": 536870912, "Mode": 1023, "Options": [["exec"]]}
            }]
          },
          "Resources": {"Limits": {"NanoCPUs": 1000000000, "MemoryBytes": 2147483648, "Pids": 512}},
          "RestartPolicy": {"Condition": "none"},
          "Placement": {"Constraints": ["node.platform.os==linux"]},
          "Networks": [{"Target": "kit-runtime"}]
        }
      }
    }
  }
}
```

Use the image's tini entrypoint and private PID namespace. The bounded tmpfs has
a sticky parent directory; the non-root supervisor creates `/state/session` with
mode `0700`. Its `workspace`, `harness` and `scratch` directories are archived.
`control` and `home` are rebuilt with current credentials. This layout creates no
local volume records. The memory limit must leave room beyond tmpfs capacity.
The supervisor maps ACP session setup to its owned workspace and supplies its
actual control directory to MCP launchers.

Keep ports unpublished. Runner must reach the runtime's service DNS name on port
8080 through the approved overlay network. Configure the controller's
`endpointTemplate` if private routing requires another address. To keep runtimes
off the internet, create the runtime overlay with `--internal`, run the egress proxy
on that overlay and on a routable network, and set the profile's `egressProxy` and
`egressHosts` as the standard installer does.

The matching `kit.harness` profile uses the normal limits, versions, scopes and
Bosca endpoint in [README.md](README.md), with these infrastructure settings:

```yaml
profile: kit-codex-swarm
infrastructure: SWARM
image: "ghcr.io/bosca-io/bosca/kit-codex-runtime@sha256:<qualified-digest>"
imageId: "sha256:<qualified-digest>"
endpointTemplate: "http://{service}:8080"
```

Swarm's `imageId` is the digest in the running task's pinned image reference.
Supervisor status separately verifies installed CLI, adapter, harness and state
policy versions. Claude uses its own image and state policy,
`authenticationMethod: null`, and an `ANTHROPIC_API_KEY` Secret reference. Keep
both harness home variables in the profile; each image reads its own variable.

## Dispatch and cleanup

Admission commits an immutable encrypted request in `kit.swarm_dispatch` with
the generation and scoped credential. Existing runtime reconciliation contacts
the controller after commit. A rolled-back admission creates no Swarm workload.
Deterministic names and a request hash let a lost response or controller restart
reuse the same generation.

The controller uses Bosca's encryption service to place generation credentials
in an owned Docker Secret. Provider and MCP credentials reference existing
Secrets. The native supervisor reads files into memory; credential values are
absent from ServiceSpec environment and archives. Scoped tokens are revoked when
their generation is retired.
Mounted generation fields supply defaults; explicit environment credentials
remain available for independent local harness processes.

Cancellation persists in PostgreSQL before contacting the manager. The controller
records it in a short-lived Docker Config, scales the Service to zero, waits for
worker shutdown, then deletes the Service and owned Secret. Unconfirmed shutdown
retains cleanup intent and admission ownership. Provider Secrets are preserved.
A five-second sweep resumes interrupted cleanup and removes expired owned
runtimes. Cancellation records expire after the hard deadline plus one minute.
Confirmed removal clears the encrypted request. Conversation purge cascades to
dispatch records after workload and archive cleanup.

Automatic process restart is disabled. An unexpected replacement has a new
supervisor identity; Runner fences it and interrupts uncertain submitted work
without replay. Queued messages and the last committed archive remain available
for explicit reactivation.

## Validation

From the workspace root:

```sh
./gradlew :cli:swarmRuntimeTest
./gradlew :ai:kit:test --tests 'bosca.ai.kit.harness.runtime.SwarmKitRuntimeProviderIntegrationTest'
node cli/src/test/resources/swarm-runtime-native.mjs <native-bosca-binary>
KIT_RUNTIME_IMAGE=<built-image> KIT_RUNTIME_FAMILY=codex KIT_RUNTIME_VERSION=<cli-version> KIT_RUNTIME_LAYOUT=swarm ./gradlew :ai:kit:runtimeImageTest
```

The controller test creates two isolated Docker daemons and checks worker
placement, isolation, Secret delivery, controller replacement, forced task
replacement and cleanup. It leaves the developer daemon's Swarm membership
unchanged. The image gate checks native CLI/MCP, ACP warm reuse, writer
termination, archival, restoration and cancellation on the Swarm filesystem
layout. Neither gate calls a model provider. Deployment routing and live provider
qualification require the target environment and account.
