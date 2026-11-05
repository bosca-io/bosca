# kubernetes

Bosca Kubernetes administration — backend.

Four modules in the root Gradle build:

- `core-kubernetes` — shared types and GraphQL schema (`io.bosca:core-kubernetes`).
- `kubernetes` — API module loaded into `bosca-server` (`io.bosca:kubernetes`).
- `kubernetes-controller` — standalone native binary that connects to clusters and serves data to the rest of the platform.
- `kubernetes-pipelines` — pipeline integration for Kubernetes operations.

Studio reaches `bosca-server` through GraphQL; the server's Kubernetes module
calls `kubernetes-controller` over HTTP and streams updates back through
GraphQL subscriptions. The controller owns cluster connections and runs the
Helm CLI for release operations. Cluster registration stores kubeconfig
encrypted in PostgreSQL. 

## Build

Run from the workspace root:

```bash
./gradlew :kubernetes:core-kubernetes:build :kubernetes:kubernetes:build :kubernetes:kubernetes-controller:build :kubernetes:kubernetes-pipelines:build
./gradlew :kubernetes:kubernetes-controller:nativeCompile
```

## Run (dev)

```bash
./gradlew :kubernetes:kubernetes-controller:installDist
./kubernetes/kubernetes-controller/build/install/kubernetes-controller/bin/kubernetes-controller
```

The service listens on port 8082.

## Queue-driven Kubernetes Jobs

The controller is the sole consumer of the physical Bosca queue
`kubernetes-jobs`. Producers enqueue a generic request with a profile name,
an idempotency key, and per-execution environment, arguments, labels, and
annotations. Each request creates exactly one Kubernetes Job.

CI routing is opt-in. Set `git.ci.kubernetesJobProfiles` (or the
`CI_KUBERNETES_JOB_PROFILES` comma-separated environment variable) to the
runner labels that should use Kubernetes. Each enabled label must match a
`JobProfile.metadata.name`.

Namespace-scoped `kubernetes.bosca.io/v1alpha1` `JobProfile`
resources hold the stable native pod template and capacity:

```yaml
apiVersion: kubernetes.bosca.io/v1alpha1
kind: JobProfile
metadata:
  name: recommendations-gpu
spec:
  maxParallelism: 2
  podTemplate:
    spec:
      restartPolicy: Never
      nodeSelector:
        accelerator: nvidia-l40s
      tolerations:
        - key: dedicated
          operator: Equal
          value: model-training
          effect: NoSchedule
      containers:
        - name: worker
          image: ghcr.io/bosca-io/bosca/recommendation-trainer:latest
          resources:
            limits:
              nvidia.com/gpu: "1"
```

The profile's `metadata.name` is its dispatch key. The pod template passes
through native scheduling, security, volume, and resource fields, including
tolerations and extended GPU resources. `maxParallelism` bounds active Jobs
for that profile.

Multiple registered clusters may expose a profile with the same name. Those
profiles form a deterministic capacity pool, allowing work to spill into
another cluster after earlier capacity is full.

The controller derives a deterministic Job name from the request's profile
and idempotency key. If the controller restarts after creating the Job but
before acknowledging the queue item, redelivery observes that Job and does
not launch a duplicate. The Bosca execution table is also a dispatch outbox:
if after-commit queue publication fails, the controller republishes the same
stable dispatch ID. Reusing `(profile, idempotencyKey)` returns the original
dispatch rather than creating an execution that can never own the deterministic
Kubernetes Job.

Every dispatch also has a durable Bosca-side execution record. Producers keep
the returned dispatch ID and call `KubernetesJobDispatchService.getResult`.
`getExecution` also exposes the worker's observed PodSpec image and kubelet
image ID. These come from a single running pod owned by the exact Job UID;
the worker is identified from the dispatch environment in the Job template.
Absent or ambiguous observations are cleared while the execution is active,
and the last observation is retained after termination. Producers can use these
fields to check deployment identity before submitting work.
It returns null while the workload can still make progress and a
`KubernetesJobResult` once Kubernetes reports `SUCCEEDED`, `FAILED`, or
`CANCELLED`. The result includes the profile, idempotency key, terminal
message, and timestamps, so CI, training, embedding, and other Bosca jobs use
one restart-safe completion contract without depending on transient events.

CI uses the pipeline job's runner label as the profile name. Its request
injects `BOSCA_CI_JOB_ID`, a uniquely scoped `BOSCA_CI_AGENT_ID`, and a
short-lived API token created only for that agent/job. The profile supplies
the Bosca endpoint, but CI profiles do not share an execution credential.
The runner removes its control-plane environment variables before launching
shell steps; built-in credential actions may deliberately configure the same
job-scoped token for the build tool they manage.
Training, embedding generation, and other workloads use the same queue with
their own profiles and entrypoints.

Generated Jobs have a result finalizer, so Kubernetes TTL cleanup and profile
deletion cannot remove the object before Bosca persists its terminal result.
Profiles default to a 24-hour active deadline and a 120-second startup-failure
grace; both are configurable per profile. Requests also have a bounded wait
to materialization, covering both a missing profile and exhausted capacity.

## Kit runtime cluster check

`runtimeClusterTest` runs the real controller reconciliation and the supplied
native runtime image against a disposable local Kind cluster. It checks duplicate
delivery after controller restart, capacity waiting, encrypted workload Secret
materialization, Service routing from another pod, readiness and control fences,
image observations, isolation settings, replacement and owner-bound cleanup.
Queue and execution persistence are fixtures in this test; their PostgreSQL
contracts and the Runner archive/restore lifecycle have separate integration tests.
This check makes no provider inference requests.

Set `KIT_RUNTIME_IMAGE` to an already built local Kit image. The image and Kind
node must have matching architectures. Use the dedicated kubeconfig below;
the test rejects other contexts and remote API servers.

```bash
export KIT_KUBERNETES_KUBECONFIG="$(mktemp)"
kind create cluster --name bosca-kit-runtime-test --kubeconfig "$KIT_KUBERNETES_KUBECONFIG"
kind load docker-image "$KIT_RUNTIME_IMAGE" --name bosca-kit-runtime-test
./gradlew :kubernetes:kubernetes-controller:runtimeClusterTest
kind delete cluster --name bosca-kit-runtime-test --kubeconfig "$KIT_KUBERNETES_KUBECONFIG"
```

The ordinary `:kubernetes:kubernetes-controller:test` suite excludes this opt-in
gate. Cluster results are always executed rather than restored from the build
cache. Passing this local gate does not qualify a deployed Runner's connectivity,
live provider behavior, or a complete matched API/Runner/controller rollout.

The CRD and profiles are installed by the
`bosca-kubernetes-controller` Helm chart through its
`jobProfiles` value. The CRD's full resource name is
`jobprofiles.kubernetes.bosca.io`.
