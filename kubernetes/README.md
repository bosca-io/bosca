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

## Controller-managed serving models

`ServingModelController` runs a separate reconciliation loop from Kubernetes Job dispatch. It discovers
exported recommendation generations through Bosca's artifact-authorized GraphQL API. A namespace-scoped
`ServingModelProfile` supplies the source URL and token Secret, native pod template, candidate capacity,
and drain interval. The `tf-serving` chart renders this profile when `provisioning.enabled` is true.

The controller creates a `ServingModel` for the active generation and each selected candidate. Each
instance captures its exact artifact versions and pod settings, and owns a Pod, static model ConfigMap,
and private Service. It does not create a Deployment or Kubernetes Job. The init container downloads
all exports before the serving container starts. Profile edits affect new instances; a loaded instance
keeps its captured template. A terminated or deleted Pod is recreated from that template.

The controller keeps active generations running while candidates download or initialize. `maxCandidates`
bounds additional Pods across the profile; draining Pods count toward that capacity. Other candidates
remain Pending. ServingModel status records Pending, Downloading, Loading, Ready, Active, Draining, or
Failed, together with the endpoint and the observed Pod message. Kubernetes retries container failures
with its normal backoff; a failed candidate never replaces the active version. After an observed failure,
`failureGraceSeconds` (default 600) allows recovery. If the candidate remains unready, the controller
reports a terminal failure to recommendations and removes the candidate to release capacity.
`startupTimeoutSeconds` (default 1800) also bounds candidate startup when no container failure is
reported, including a stalled download, unschedulable Pod, or model that never becomes ready. Waiting
for candidate capacity does not consume this deadline. A new selection revision resets the deadline;
Pod recreation does not. Failure reports carry the observed selection revision, so a delayed report
cannot clear a newer request for the same version. A completed
rollback target remains in history, and a later explicit activation clears its previous serving failure.

Serving preparation and failure reporting use `controlTokenSecretName` / `controlTokenSecretKey`, separately from the read-only
discovery token. The credential must have administrator access or artifact push permissions for both
matching exports. The TF Serving chart uses its configured `loader.secrets.boscaToken` reference for this
credential. If reporting fails, the controller preserves resources and retries rather than losing the failure.

The recommendation activation job checks the candidate's exact content and personalized status endpoints
before the existing selection service changes the active version. For a version-specific endpoint, the
controller records the selection revision on the ServingModel, then reports
`modelServingPrepared` with its endpoint and queues validation. The runner accepts that claim only for
its configured endpoint. Initial export jobs do not activate a provisioned version before this claim.
Completed jobs for obsolete selections or already-active versions exit without polling. Configure
`RECOMMENDATIONS_CONTEXT_MODEL_URL_TEMPLATE` on both server and runner to match the profile's Service
prefix, namespace and port, for example `http://tf-serving-model-{version}.models.svc:8501`. Predictions
and validation resolve the same endpoint. Requests retain their selected version and endpoint, so changing
the active version does not redirect in-flight calls to another generation.

Reconciliation and activation share a renewed distributed lifecycle lock, including exact-version
validation and the selection update. Controller and runner must use the same configured distributed lock
backend. Reconciliation reads selection only after acquiring the lock and uses one Pod snapshot for
capacity and readiness checks. Recording a new selection revision changes the ServingModel resource
version before activation can be queued; a delayed retirement request carrying an
older resource version fails even if its former lock holder's HTTP write completes after lease loss.
The controller also deletes terminal Pods conditionally, so a delayed delete cannot remove a replacement
Pod with the same name. Overlapping controller replicas share candidate capacity.

Unselected instances drain for `drainSeconds` before ownership garbage collection removes their resources.
Discovery or authorization failures preserve existing resources. Stable Kubernetes names and owner UIDs
allow reconciliation after controller restarts, and prevent adopting unrelated resources with the same name.
Deleting a profile explicitly deletes its owned instances. Artifact retention remains owned by recommendations.

The controller chart installs `servingmodelprofiles.kubernetes.bosca.io` and
`servingmodels.kubernetes.bosca.io`. Its `servingModelProfiles` value also accepts native profile specs.
The existing registered-cluster credentials need permissions for these resources, Pods, Services,
ConfigMaps and the discovery token Secret. The controller chart's existing default role covers them.

The lifecycle integration test uses a real isolated Kubernetes API. Set `SERVING_MODEL_TEST_KUBECONFIG`
to an isolated cluster with `node:22-alpine` available, then run from the workspace root:

```bash
./gradlew :kubernetes:kubernetes-controller:test --tests 'bosca.kubernetes.controller.models.*'
```

The test installs the two CRDs and creates a temporary namespace. Use a test cluster: the CRDs remain
installed after the namespace is removed. The model-loader suite separately exercises real TensorFlow
Serving in Docker when `TF_SERVING_EXPORT_PYTHON` points to an interpreter with TensorFlow installed.
