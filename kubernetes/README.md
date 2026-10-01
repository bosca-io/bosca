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

The CRD and profiles are installed by the
`bosca-kubernetes-controller` Helm chart through its
`jobProfiles` value. The CRD's full resource name is
`jobprofiles.kubernetes.bosca.io`.
