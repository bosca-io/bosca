# Kit on Kubernetes

The Kubernetes provider runs each admitted conversation generation as a Job. The Job contains the native supervisor and harness in one container with their writable workspace/state. It is kept alive across turns and removed after retirement. It is not a new Job for every message.

The Runner owns conversation state and archive commitment. The separate Bosca Kubernetes controller owns workload creation and Kubernetes credentials. The Runner uses the existing durable job-dispatch service; it does not directly run a Kubernetes client or Helm inside a chat request.

## Standard Helm deployment

In the published `bosca-services` chart, enable Kit in the same values file you use for the platform:

```yaml
global:
  kit:
    enabled: true
    harness: codex
```

Run your normal `helm upgrade --install` for that release. The chart supplies one matching runtime profile to API and Runner, creates the JobProfile, and enables Runner's Kubernetes support. Published charts bundle the runtime's immutable image identity and native component versions. You do not copy those values from a running pod or create a private Kit policy for the standard configuration.

The default Codex profile uses the existing `openai-token` Secret's `token` key. To use Claude, select `harness: claude` and provide `anthropic-token/token`. An existing provider Secret can be selected with `global.kit.providerSecret.name` and `.key`. Keep the Secret in the release namespace. Provider access and account entitlements belong to that deployment.

Optional overrides include `global.kit.profile.idleSeconds`, `.profile.scopes`, admission limits, workload resources, workspace/state sizes, and node selectors. API, Runner, and the JobProfile use the same generated profile. Kit archives use the platform's configured object storage.

The chart also isolates Kit runtimes from the internet. It installs the Kit egress proxy and the network policies described in [Outbound access](#outbound-access). Set `global.kit.egress.enabled: false` only when you provide equivalent isolation yourself.

Kit Jobs use the controller chart's existing `imagePullSecrets` setting, the same as its other Pods. Its default is `docker-registry`; set `bosca-kubernetes-controller.imagePullSecrets` when your registry uses another Secret. Renaming the API Service through `bosca-server.nameOverride` or `fullnameOverride` also requires `global.kit.boscaEndpoint` to identify that Service's GraphQL URL. The chart checks this before installation instead of generating an address for a Service that does not exist.

The remaining sections describe custom installations and explain the generated workload. They are not additional steps for a standard Helm deployment.

## Custom installation prerequisites

Provide a registered cluster, the controller and JobProfile CRD, and compatible API/Runner builds with the Kubernetes module enabled. The controller needs the platform database and matching encryption configuration to read its encrypted cluster credentials and materialize generation credentials.

Both API and Runner need the same deployment profiles from [Configuration](/kit-reference/configuration). Enable `kubernetes.enabled` (or the packaged `KUBERNETES_ENABLED` setting). Kit uses the existing durable Kubernetes dispatch queue and database execution records; it does not change Runner's controller HTTP routing. The Runner must process the existing content queue, normally through `contentQueueRunner`. Preserve the installation's existing `KUBERNETES_CONTROLLER_URL` configuration for other controller HTTP operations.

Use the deployment's operations repository for Helm values, Secrets, and adapted JobProfiles. The egress proxy and the Kit network policies are part of the `bosca-services` umbrella chart. Installing the controller chart by itself renders neither, and its generated profile has no `egressProxy` unless `global.kit.egress.enabled` is set. A custom installation supplies its own proxy and policies, or runs without them. The workspace includes `helm/bosca-kubernetes-controller/` and its `jobProfiles` values, plus the CRD at `helm/bosca-kubernetes-controller/crds/jobprofiles.kubernetes.bosca.io.yaml`. Configure cluster access through the existing Studio Kubernetes administration.

## Build and qualify the image

The release helper builds Linux amd64 native images and requires a Linux x86_64 host. Run it from the workspace root with the matching native CLI sources. `PUSH=false` creates a local image and build evidence without publication:

```sh
PUSH=false scripts/release/build-image.sh kit-codex-runtime YOUR_VERSION
PUSH=false scripts/release/build-image.sh kit-claude-runtime YOUR_VERSION
```

The image release gate exercises the native supervisor and installed adapters with synthetic ACP turns and local MCP fixtures. It does not certify live provider behavior. Qualify the actual image, architecture, provider account, networking, and cold return before enabling it for users.

For a custom image, supply its digest-pinned `image`, configuration digest as `imageId`, and component `versions`. Published chart releases obtain these from the image automatically. Kubelet may report the pinned manifest digest or the image's configuration digest; both identify the admitted image. The controller records the single running worker owned by the exact Job UID. Missing image observations wait within the startup deadline; mismatches fail readiness, revoke the generation credential, and remove the workload.

## Adapt a JobProfile

The full starting point is `ai/kit/runtime/job-profile.example.yaml`. Its provider Secret is supplied by operations; generation credentials are created independently by the controller. This abbreviated profile shows the important contracts. Replace the image placeholder and supply your resource/network settings:

```yaml
apiVersion: kubernetes.bosca.io/v1alpha1
kind: JobProfile
metadata:
  name: kit-codex
  namespace: kit
spec:
  maxParallelism: 10
  workerContainerName: worker
  job:
    backoffLimit: 0
    activeDeadlineSeconds: 86400
    startupFailureGraceSeconds: 120
    ttlSecondsAfterFinished: 0
  podTemplate:
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: 60
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: worker
          image: ghcr.io/bosca-io/bosca/kit-codex-runtime@sha256:QUALIFIED_DIGEST
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          resources:
            requests:
              cpu: "1"
              memory: 1Gi
              ephemeral-storage: 2Gi
            limits:
              cpu: "2"
              memory: 2Gi
              ephemeral-storage: 32Gi
          env:
            - name: OPENAI_API_KEY
              valueFrom:
                secretKeyRef:
                  name: kit-codex-provider
                  key: api-key
          ports:
            - name: runtime
              containerPort: 8080
          readinessProbe:
            httpGet: { path: /readyz, port: runtime }
          livenessProbe:
            httpGet: { path: /healthz, port: runtime }
          volumeMounts:
            - { name: workspace, mountPath: /workspace }
            - { name: state, mountPath: /state }
      volumes:
        - name: workspace
          emptyDir: { sizeLimit: 8Gi }
        - name: state
          emptyDir: { sizeLimit: 24Gi }
```

Use the image's init entrypoint and its own PID namespace. Do not share host PID state, mount a Docker socket, or enable automatic native-process replacement to keep a generation apparently alive. A replacement has a different supervisor identity and must be reconciled.

The Job's active deadline must cover the profile's hard runtime. Leave enough termination grace for native writer shutdown. `backoffLimit: 0` and `restartPolicy: Never` avoid treating an automatically restarted process as the original generation.

`ttlSecondsAfterFinished: 0` allows failed Jobs and their owned Services and Secrets to be collected. The Bosca result finalizer keeps the Job until its terminal result is durable. A longer TTL deliberately retains those resources for that period, even though the scoped credential has been revoked.

Claude needs a separate JobProfile and image, an `ANTHROPIC_API_KEY` Secret reference, the Claude compatibility versions, and `authenticationMethod: null` in its deployment profile. Do not mount a developer's login directory as provider authentication.

## Storage and scheduling

Workspace and native state stay together in the workload. The supplied `emptyDir` volumes are temporary: an archive in shared object storage is the cold-return boundary. No ReadWriteMany volume is required. If you adapt storage, preserve co-location and the fixed mount paths; a PVC alone does not replace verified archival or make uncertain active work replayable.

Restore preserves the deployment's permissions on fixed mount roots and restores modes on their descendants. `fsGroup` allows the unprivileged user to write a root-owned mounted volume. Size writable storage for the native state, workspace, archive staging, and temporary work. Namespace quotas, JobProfile parallelism, and Kit's admission limits can each prevent scheduling.

## Private network paths

| Path | Purpose |
| --- | --- |
| API/Runner to controller | Durable workload dispatch, observation, and removal. |
| Runner to runtime Service on 8080 | Authenticated control, ACP, archive transfer, and restore. |
| Runtime to Bosca API on 8080 | Scoped MCP calls. Set `boscaEndpoint` to an address reachable from the workload. |
| Runtime to Kit egress proxy on 3128 | Every other destination: the model provider, remote MCP servers, Bosca's public hostnames, and administrator-approved hosts. |
| Egress proxy to Bosca API on 8080 | The proxy looks up the destinations approved for each runtime. |
| Egress proxy to approved destinations | HTTPS tunnels opened for the runtime. |
| Runner to private object storage | Verified archive storage and retrieval. |

The usual endpoint template is `http://{job}.{namespace}.svc:8080`. A Runner outside that cluster cannot use cluster-local DNS merely because the API can reach Kubernetes. Place the Runner where the runtime network is reachable or provide an appropriate private route and endpoint template. Supported Kubernetes substitutions are `{job}`, `{namespace}`, and `{cluster}`.

The controller creates a Job-owned internal Service and a separate immutable generation Secret. Keep operator-managed provider Secrets independent so generation cleanup does not remove them.

## Outbound access

Kit runtimes have no direct internet access. They can reach Bosca's API, and they reach every other destination through the Kit egress proxy. With `global.kit.enabled` and `global.kit.egress.enabled` (the default), the `bosca-services` chart adds:

- A `<release>-kit-egress` Deployment and Service on port 3128. It runs `bosca harness egress` from the `bosca-cli` image with one replica, a non-root read-only container, and no service account token. The image tag defaults to the CLI release recorded for the bundled Kit runtime image, the registry follows `global.imageRegistry`, and image pull secrets follow `bosca-kubernetes-controller.imagePullSecrets`. `global.kit.egress` can set `replicas`, `resources`, `image`, `imagePullSecrets`, `nodeSelector`, and `tolerations`.
- `egressProxy: http://<release>-kit-egress.<namespace>.svc:3128` in the generated profile, with `egressHosts` set to `api.openai.com:443` for Codex or `api.anthropic.com:443` for Claude. `global.kit.profile.egressHosts` replaces that list.
- A `<release>-kit-runtime` NetworkPolicy for the Kit runtime Pods, selected by their `kubernetes.bosca.io/job-profile` label. They accept connections only from Runner Pods on port 8080. They can connect only to cluster DNS (UDP and TCP 53 to `k8s-app: kube-dns` Pods in any namespace), bosca-server Pods on 8080, and the egress proxy on 3128.
- A `<release>-kit-egress` NetworkPolicy for the proxy. It accepts connections only from Kit runtime Pods on 3128. It can connect to cluster DNS, bosca-server Pods on 8080, the Pods behind the release's public Bosca routes on their routed ports, and IPv4 addresses outside 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 100.64.0.0/10, and 127.0.0.0/8.

The runtime supervisor gives the harness and the processes it starts proxy settings that carry the session's own egress credential. The proxy supports only HTTPS `CONNECT` requests with that credential and permits:

- every Gateway hostname of the release on port 443, which are Bosca's own entry points;
- the provider hosts in the profile's `egressHosts`;
- the remote MCP registrations assigned to the session's agent;
- the `egressHosts` list in the private `kit.harness` policy, which administrators edit in Studio. See [Outbound destinations](/kit-reference/configuration#outbound-destinations).

For any destination other than a Gateway hostname, the proxy resolves the host. It refuses the destination if any resolved address is private, loopback, link-local, shared (100.64.0.0/10), unique-local, multicast, or unspecified.

NetworkPolicy takes effect only when the cluster's network plugin enforces it. Without enforcement, the proxy still applies its destination rules to traffic that uses it, but nothing prevents a runtime from connecting directly. Confirm that your network plugin enforces both policies, and that the proxy can reach the Gateway's public address from inside the cluster.

## Verify the installation

Run one isolated conversation through two warm turns, idle archive, workload removal, resume, another turn, cancellation, and deletion. Verify the actual image observations and component versions. Check that the restored workspace belongs to a new generation, old credentials are revoked, and archives and generation resources are removed after deletion.

The local source gate `:kubernetes:kubernetes-controller:runtimeClusterTest` uses a disposable Kind cluster and an already built native image. Follow `kubernetes/README.md` for its explicit kubeconfig and context requirements. It uses fixture queue/persistence contracts; its success is not a complete deployed Runner or live-provider qualification.
