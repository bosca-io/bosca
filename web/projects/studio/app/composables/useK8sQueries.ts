import { computed, type ComputedRef, type MaybeRefOrGetter, type Ref, toValue } from 'vue'
import type {
  ConfigKind,
  EventLevel,
  HelmStatus,
  K8sCertificate,
  K8sCluster,
  K8sCnpgBackup,
  K8sCnpgCluster,
  K8sCnpgClusterDetail,
  K8sCnpgInstance,
  K8sCnpgParameter,
  K8sConfigResource,
  K8sCustomResource,
  K8sEvent,
  K8sGateway,
  K8sGatewayClass,
  K8sHelmChart,
  K8sHelmRelease,
  K8sHelmRepo,
  K8sHpa,
  K8sHpaMetric,
  K8sHttpRoute,
  K8sIngress,
  K8sIssuer,
  K8sNetworkPolicy,
  K8sNode,
  K8sOperator,
  K8sPdb,
  K8sPodWithWorkload,
  K8sPVC,
  K8sRole,
  K8sRoleBinding,
  K8sService,
  K8sServiceAccount,
  K8sStorageClass,
  K8sWorkload,
  WorkloadKind,
} from './useK8sTypes'

type AsyncStatus = 'idle' | 'pending' | 'success' | 'error'

/**
 * Live GraphQL composables for kubernetes resources.
 *
 * Each composable issues a real query against `kubernetes` and maps the
 * backend's schema shape onto the `K8sX` view types pages consume. The
 * adapter layer absorbs minor differences between the GraphQL wire
 * format and what the templates render so that adding a backend field
 * is a one-place change.
 */

// ---------------------------------------------------------------------
// Adapters — GraphQL types → K8sX view types
// ---------------------------------------------------------------------

const HEALTH_MAP: Record<string, K8sCluster['health']> = {
  OK: 'ok',
  WARN: 'warn',
  ERROR: 'err',
}
const ENV_MAP: Record<string, K8sCluster['env']> = {
  PRODUCTION: 'production',
  STAGING: 'staging',
  DEVELOPMENT: 'dev',
}
const STATUS_MAP: Record<string, 'ok' | 'warn' | 'err'> = {
  OK: 'ok',
  WARN: 'warn',
  ERROR: 'err',
}
// Workloads carry the richer four-state enum — PENDING marks a rollout
// still coming up (pods scheduling / pulling / creating), distinct from
// a genuinely failing workload. Other resource kinds stay tri-state.
const WORKLOAD_STATUS_MAP: Record<string, K8sWorkload['status']> = {
  OK: 'ok',
  PENDING: 'pending',
  WARN: 'warn',
  ERROR: 'err',
}
const EVENT_LEVEL_MAP: Record<string, EventLevel> = {
  INFO: 'info',
  WARN: 'warn',
  ERROR: 'error',
}
const WORKLOAD_KIND_MAP: Record<string, WorkloadKind> = {
  DEPLOYMENT: 'Deployment',
  STATEFUL_SET: 'StatefulSet',
  DAEMON_SET: 'DaemonSet',
  REPLICA_SET: 'ReplicaSet',
  JOB: 'Job',
  CRON_JOB: 'CronJob',
}
const HELM_STATUS_MAP: Record<string, HelmStatus> = {
  DEPLOYED: 'deployed',
  PENDING: 'pending',
  FAILED: 'failed',
  SUPERSEDED: 'superseded',
  UNINSTALLED: 'failed',
}

interface GqlCluster {
  id: string
  name: string
  provider: string
  region: string
  environment: string
  version: string
  health: string
  nodes: number
  pods: number
}

function toMockCluster(c: GqlCluster): K8sCluster {
  return {
    id: c.id,
    name: c.name,
    provider: c.provider,
    region: c.region,
    env: ENV_MAP[c.environment] ?? 'dev',
    health: HEALTH_MAP[c.health] ?? 'warn',
    version: c.version,
    nodes: c.nodes,
    pods: c.pods,
  }
}

interface GqlWorkload {
  id: string
  kind: string
  name: string
  namespace: string
  ready: number
  desired: number
  status: string
  image: string
  age: string
  cpu: number
  memory: number
  restarts: number
  strategy: string
}

function toMockWorkload(w: GqlWorkload): K8sWorkload {
  const kind = WORKLOAD_KIND_MAP[w.kind] ?? 'Deployment'
  const isPodless = kind === 'Job' || kind === 'CronJob'
  return {
    id: w.id,
    kind,
    name: w.name,
    ns: w.namespace,
    replicas: isPodless ? '—' : `${w.ready}/${w.desired}`,
    ready: w.ready,
    want: w.desired,
    status: WORKLOAD_STATUS_MAP[w.status] ?? 'warn',
    image: w.image,
    age: w.age,
    cpu: w.cpu,
    mem: w.memory,
    restarts: w.restarts,
    strategy: w.strategy || '—',
  }
}

interface GqlPod {
  id: string
  name: string
  namespace: string
  node: string
  status: string
  ready: string
  restarts: number
  age: string
  cpu: number
  memory: number
  workload: GqlWorkload | null
  image: string
  workloadKind: string | null
  workloadName: string | null
}

function toMockPod(p: GqlPod, fallback: K8sWorkload): K8sPodWithWorkload {
  // Pod intrinsic fields (image, owner kind/name) are now on the
  // GraphQL Pod type itself — the workload-by-id resolver on the
  // server still returns null, so the studio composes a synthetic
  // K8sWorkload from the pod's own metadata. That keeps the table
  // and the pod detail page showing a useful image / kind / name
  // without paying for the workload N+1 lookup.
  const synthesised: K8sWorkload | undefined = p.workload
    ? toMockWorkload(p.workload)
    : (p.workloadKind && p.workloadName)
      ? {
          ...fallback,
          ns: p.namespace,
          name: p.workloadName,
          kind: (WORKLOAD_KIND_MAP[p.workloadKind] ?? fallback.kind),
          image: p.image,
        }
      : { ...fallback, ns: p.namespace, name: p.name, image: p.image }
  return {
    name: p.name,
    node: p.node,
    status: p.status,
    ready: p.ready,
    restarts: p.restarts,
    age: p.age,
    cpu: p.cpu,
    mem: p.memory,
    workload: synthesised,
  }
}

interface GqlNode {
  name: string
  role: string
  instance: string
  zone: string
  status: string
  cpu: number
  memory: number
  pods: number
  age: string
  version: string
  taints: string[]
  labels: Record<string, string> | null
}

function toMockNode(n: GqlNode): K8sNode {
  return {
    name: n.name,
    role: (n.role === 'control-plane' ? 'control-plane' : 'worker'),
    instance: n.instance,
    zone: n.zone,
    status: (n.status as K8sNode['status']),
    cpu: n.cpu,
    mem: n.memory,
    pods: n.pods,
    age: n.age,
    version: n.version,
    taints: n.taints,
    labels: n.labels ?? {},
  }
}

interface GqlEvent {
  id: string
  level: string
  when: string
  namespace: string
  involvedObject: string
  message: string
  reason: string
}

function toMockEvent(e: GqlEvent, idx: number): K8sEvent {
  // Mock keeps a numeric id for legacy reasons; the GraphQL id is
  // a stable opaque string. We expose the numeric index because
  // K8sEventRow only uses it as a v-for key — uniqueness within
  // the page is all that matters.
  const numericId = Number.parseInt(e.id.replace(/\D/g, '').slice(0, 9), 10)
  return {
    id: Number.isFinite(numericId) && numericId > 0 ? numericId : idx + 1,
    lvl: EVENT_LEVEL_MAP[e.level] ?? 'info',
    when: e.when,
    ns: e.namespace,
    obj: e.involvedObject,
    msg: e.message,
    reason: e.reason,
  }
}

interface GqlNamespace {
  name: string
  status: string
  workloads: number
  pods: number
  services: number
  age: string
}

export interface K8sNamespaceCard {
  name: string
  status: string
  workloads: number
  pods: number
  services: number
  age: string
}

interface GqlService {
  id: string
  name: string
  namespace: string
  type: string
  clusterIP: string
  externalIP: string
  ports: string[]
  selector: string
  age: string
  endpoints: number
}

function toMockService(s: GqlService): K8sService {
  return {
    id: s.id,
    name: s.name,
    ns: s.namespace,
    type: (s.type as K8sService['type']),
    clusterIP: s.clusterIP,
    externalIP: s.externalIP,
    ports: s.ports,
    selector: s.selector,
    age: s.age,
    endpoints: s.endpoints,
  }
}

interface GqlIngress {
  id: string
  name: string
  namespace: string
  ingressClass: string
  hosts: string[]
  paths: string[]
  backends: string[]
  tls: boolean
  age: string
}

function toMockIngress(i: GqlIngress): K8sIngress {
  return {
    id: i.id,
    name: i.name,
    ns: i.namespace,
    class: i.ingressClass,
    hosts: i.hosts,
    paths: i.paths,
    backends: i.backends,
    tls: i.tls,
    age: i.age,
  }
}

interface GqlNetworkPolicy {
  id: string
  name: string
  namespace: string
  podSelector: string
  ingress: string
  egress: string
  age: string
}

function toMockNetworkPolicy(n: GqlNetworkPolicy): K8sNetworkPolicy {
  return {
    id: n.id,
    name: n.name,
    ns: n.namespace,
    podSelector: n.podSelector,
    ingress: n.ingress,
    egress: n.egress,
    age: n.age,
  }
}

interface GqlGatewayClass {
  name: string
  controller: string
  accepted: boolean
  age: string
}

function toMockGatewayClass(g: GqlGatewayClass): K8sGatewayClass {
  return { name: g.name, controller: g.controller, accepted: g.accepted, age: g.age }
}

interface GqlGateway {
  id: string
  name: string
  namespace: string
  gatewayClass: string
  addresses: string[]
  listeners: number
  routes: number
  status: string
  age: string
}

function toMockGateway(g: GqlGateway): K8sGateway {
  return {
    id: g.id,
    name: g.name,
    ns: g.namespace,
    class: g.gatewayClass,
    addresses: g.addresses,
    listeners: g.listeners,
    routes: g.routes,
    status: STATUS_MAP[g.status] ?? 'warn',
    age: g.age,
  }
}

interface GqlHttpRoute {
  id: string
  name: string
  namespace: string
  parents: string[]
  hosts: string[]
  paths: string[]
  backends: string[]
  rules: number
  age: string
  status: string
}

function toMockHttpRoute(r: GqlHttpRoute): K8sHttpRoute {
  return {
    id: r.id,
    name: r.name,
    ns: r.namespace,
    parents: r.parents,
    hosts: r.hosts,
    paths: r.paths,
    backends: r.backends,
    rules: r.rules,
    age: r.age,
    status: STATUS_MAP[r.status] ?? 'warn',
  }
}

interface GqlConfigResource {
  id: string
  kind: string
  name: string
  namespace: string
  keys: string[]
  size: string
  age: string
  secretType: string | null
  managedBy: string | null
}

function toMockConfigResource(c: GqlConfigResource): K8sConfigResource {
  return {
    id: c.id,
    kind: (c.kind === 'CONFIG_MAP' ? 'ConfigMap' : 'Secret') as ConfigKind,
    name: c.name,
    ns: c.namespace,
    keys: c.keys,
    size: c.size,
    age: c.age,
    type: c.secretType ?? undefined,
    managedBy: c.managedBy ?? undefined,
  }
}

interface GqlConfigEntry {
  key: string
  value: string
}

interface GqlStorageClass {
  name: string
  provisioner: string
  reclaim: string
  binding: string
  isDefault: boolean
  age: string
  parameters: string
}

function toMockStorageClass(s: GqlStorageClass): K8sStorageClass {
  return {
    name: s.name,
    provisioner: s.provisioner,
    reclaim: (s.reclaim as K8sStorageClass['reclaim']),
    binding: (s.binding as K8sStorageClass['binding']),
    default: s.isDefault,
    age: s.age,
    params: s.parameters,
  }
}

interface GqlPvc {
  id: string
  name: string
  namespace: string
  status: string
  volume: string
  capacity: string
  accessMode: string
  storageClass: string
  age: string
  usedPercent: number | null
  workload: string | null
}

function toMockPvc(p: GqlPvc): K8sPVC {
  return {
    id: p.id,
    name: p.name,
    ns: p.namespace,
    status: (p.status as K8sPVC['status']),
    volume: p.volume,
    capacity: p.capacity,
    access: (p.accessMode as K8sPVC['access']),
    storageClass: p.storageClass,
    age: p.age,
    usedPct: p.usedPercent ?? 0,
    workload: p.workload ?? '',
  }
}

interface GqlOperator {
  id: string
  name: string
  version: string
  group: string
  namespace: string
  status: string
  instances: number
  kinds: string[]
  description: string
}

function toMockOperator(o: GqlOperator): K8sOperator {
  return {
    id: o.id,
    name: o.name,
    version: o.version,
    group: o.group,
    ns: o.namespace,
    status: STATUS_MAP[o.status] ?? 'warn',
    instances: o.instances,
    crds: o.kinds,
    desc: o.description,
  }
}

interface GqlCustomResource {
  id: string
  kind: string
  group: string
  version: string
  namespace: string
  name: string
  age: string
  status: string
  detail: string
}

function toMockCustomResource(c: GqlCustomResource): K8sCustomResource {
  return {
    id: c.id,
    kind: c.kind,
    group: c.group,
    version: c.version,
    ns: c.namespace,
    name: c.name,
    age: c.age,
    status: STATUS_MAP[c.status] ?? 'warn',
    detail: c.detail,
  }
}

interface GqlRole {
  id: string
  kind: string
  name: string
  builtin: boolean
  bindings: number
  rules: number
  age: string
  namespace: string | null
  description: string
}

function toMockRole(r: GqlRole): K8sRole {
  return {
    id: r.id,
    kind: (r.kind === 'ClusterRole' ? 'ClusterRole' : 'Role'),
    name: r.name,
    builtin: r.builtin,
    bindings: r.bindings,
    rules: r.rules,
    age: r.age,
    ns: r.namespace ?? undefined,
    desc: r.description,
  }
}

interface GqlRoleSubject {
  kind: string
  name: string
  namespace: string | null
}

interface GqlRoleBinding {
  id: string
  kind: string
  name: string
  role: string
  subjects: GqlRoleSubject[]
  namespace: string | null
  age: string
}

function toMockRoleBinding(b: GqlRoleBinding): K8sRoleBinding {
  return {
    id: b.id,
    kind: (b.kind === 'ClusterRoleBinding' ? 'ClusterRoleBinding' : 'RoleBinding'),
    name: b.name,
    role: b.role,
    subjects: b.subjects.map(s => ({
      kind: (s.kind as 'User' | 'Group' | 'ServiceAccount'),
      name: s.name,
      ns: s.namespace ?? undefined,
    })),
    ns: b.namespace ?? undefined,
    age: b.age,
  }
}

interface GqlServiceAccount {
  id: string
  name: string
  namespace: string
  pods: number
  secrets: number
  bindings: number
  age: string
  iamRole: string | null
}

function toMockServiceAccount(a: GqlServiceAccount): K8sServiceAccount {
  return {
    id: a.id,
    name: a.name,
    ns: a.namespace,
    pods: a.pods,
    secrets: a.secrets,
    bindings: a.bindings,
    age: a.age,
    irsa: a.iamRole ?? undefined,
  }
}

interface GqlCertificate {
  id: string
  name: string
  namespace: string
  dns: string[]
  issuer: string
  status: string
  readySince: string
  expiresInDays: number
  renewsInDays: number
  secretName: string
  error: string | null
}

function toMockCertificate(c: GqlCertificate): K8sCertificate {
  return {
    id: c.id,
    name: c.name,
    ns: c.namespace,
    dns: c.dns,
    issuer: c.issuer,
    status: (c.status as K8sCertificate['status']),
    readySince: c.readySince,
    expires: c.expiresInDays,
    renewsIn: c.renewsInDays,
    secretName: c.secretName,
    error: c.error ?? undefined,
  }
}

interface GqlIssuer {
  id: string
  kind: string
  name: string
  type: string
  server: string
  status: string
  age: string
  certs: number
  namespace: string | null
}

function toMockIssuer(i: GqlIssuer): K8sIssuer {
  return {
    id: i.id,
    kind: (i.kind === 'ClusterIssuer' ? 'ClusterIssuer' : 'Issuer'),
    name: i.name,
    type: (i.type as K8sIssuer['type']),
    server: i.server,
    status: (i.status as K8sIssuer['status']),
    age: i.age,
    certs: i.certs,
    ns: i.namespace ?? undefined,
  }
}


interface GqlCnpgCluster {
  name: string
  namespace: string
  instances: number
  primary: string
  postgresVersion: string
  image: string
  status: string
  statusKind: string
  age: string
  backupSchedule: string
  lastBackup: string
}

function toMockCnpgCluster(c: GqlCnpgCluster): K8sCnpgCluster {
  return {
    name: c.name,
    namespace: c.namespace,
    instances: c.instances,
    primary: c.primary,
    pgVersion: c.postgresVersion,
    image: c.image,
    status: c.status,
    statusKind: STATUS_MAP[c.statusKind] ?? 'warn',
    age: c.age,
    backupSchedule: c.backupSchedule,
    lastBackup: c.lastBackup,
  }
}

interface GqlCnpgInstance {
  name: string
  role: string
  status: string
  ready: boolean
  node: string
  zone: string
  pvcSize: string
  restarts: number
  age: string
}

interface GqlCnpgBackup {
  name: string
  method: string
  phase: string
  statusKind: string
  started: string
  completed: string
  duration: string
}

interface GqlCnpgParameter {
  key: string
  value: string
}

interface GqlCnpgClusterDetail {
  cluster: GqlCnpgCluster
  storageSize: string
  storageClass: string
  backupDestinationPath: string
  backupRetention: string
  instances: GqlCnpgInstance[]
  backups: GqlCnpgBackup[]
  parameters: GqlCnpgParameter[]
}

function toCnpgInstance(i: GqlCnpgInstance): K8sCnpgInstance {
  return {
    name: i.name,
    role: i.role,
    status: i.status,
    ready: i.ready,
    node: i.node,
    zone: i.zone,
    pvcSize: i.pvcSize,
    restarts: i.restarts,
    age: i.age,
  }
}

function toCnpgBackup(b: GqlCnpgBackup): K8sCnpgBackup {
  return {
    name: b.name,
    method: b.method,
    phase: b.phase,
    statusKind: STATUS_MAP[b.statusKind] ?? 'warn',
    started: b.started,
    completed: b.completed,
    duration: b.duration,
  }
}

function toCnpgClusterDetail(d: GqlCnpgClusterDetail): K8sCnpgClusterDetail {
  return {
    cluster: toMockCnpgCluster(d.cluster),
    storageSize: d.storageSize,
    storageClass: d.storageClass,
    backupDestinationPath: d.backupDestinationPath,
    backupRetention: d.backupRetention,
    instances: d.instances.map(toCnpgInstance),
    backups: d.backups.map(toCnpgBackup),
    parameters: d.parameters.map((p): K8sCnpgParameter => ({ key: p.key, value: p.value })),
  }
}

interface GqlHelmRepo {
  name: string
  url: string
  type: string
  charts: number
  lastUpdate: string
}

function toMockHelmRepo(r: GqlHelmRepo): K8sHelmRepo {
  return {
    name: r.name,
    url: r.url,
    type: (r.type as K8sHelmRepo['type']),
    charts: r.charts,
    lastUpdate: r.lastUpdate,
  }
}

interface GqlHelmChart {
  id: string
  name: string
  repo: string
  version: string
  appVersion: string
  description: string
  icon: string | null
}

function toMockHelmChart(c: GqlHelmChart): K8sHelmChart {
  return {
    id: c.id,
    name: c.name,
    repo: c.repo,
    version: c.version,
    appVersion: c.appVersion,
    description: c.description,
    icon: c.icon ?? undefined,
  }
}

interface GqlHelmRelease {
  id: string
  name: string
  namespace: string
  chart: string
  chartVersion: string
  appVersion: string
  revision: number
  status: string
  updated: string
  installed: string
  repo: string
  repoUrl: string
  description: string
}

function toMockHelmRelease(r: GqlHelmRelease): K8sHelmRelease {
  return {
    id: r.id,
    name: r.name,
    ns: r.namespace,
    chart: r.chart,
    chartVersion: r.chartVersion,
    appVersion: r.appVersion,
    revision: r.revision,
    status: HELM_STATUS_MAP[r.status] ?? 'pending',
    updated: r.updated,
    installed: r.installed,
    repo: r.repo,
    repoUrl: r.repoUrl,
    description: r.description,
  }
}

export interface K8sHelmRevisionRow {
  revision: number
  updated: string
  status: HelmStatus
  chart: string
  appVersion: string
  description: string
}

export interface K8sHelmChartVersionRow {
  version: string
  appVersion: string
  released: string
  current: boolean
}

export interface K8sHelmChartValuesRow {
  defaultValues: string
  schema: unknown
}

// ---------------------------------------------------------------------
// Composables — one per resource
// ---------------------------------------------------------------------

/**
 * Wraps a `useAsyncQuery` result so callers see the mapped slice
 * (`K8sX`) instead of the raw GraphQL payload. We return the original
 * `refresh` so pages keep using the same `{ data, status, refresh }`
 * triple they always have.
 *
 * `status` reports `pending` only while there is no payload yet (first
 * load, or a variable change that moved to a fresh `useAsyncData` key).
 * Background refreshes — the resource-watch composable re-runs every
 * page's `refresh()` on cluster changes — keep the previous payload, so
 * surfacing them as `pending` made each watch tick swap the rendered
 * table for the loading state and back: the page "flashed". With data
 * in hand we stay `success` and let the rows update in place.
 */
function mapped<T, U>(
  result: { data: { value: T | null | undefined }; status: Ref<AsyncStatus> | { value: AsyncStatus }; refresh: () => Promise<unknown> },
  pick: (data: T | null | undefined) => U,
) {
  const data = computed<U>(() => pick(result.data.value))
  const status = computed<AsyncStatus>(() => {
    const s = result.status.value
    return s === 'pending' && result.data.value != null ? 'success' : s
  })
  return {
    data,
    status,
    refresh: result.refresh,
  }
}

// Clusters --------------------------------------------------------------

const Q_CLUSTERS = `
  query K8sClusters {
    kubernetes {
      clusters {
        id name provider region environment version health nodes pods
      }
    }
  }
`

export function useK8sClusters() {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { clusters: GqlCluster[] } }>('k8s.clusters', Q_CLUSTERS)
  return mapped<{ kubernetes: { clusters: GqlCluster[] } }, K8sCluster[]>(
    result,
    d => (d?.kubernetes?.clusters ?? []).map(toMockCluster),
  )
}

// Workloads -------------------------------------------------------------

export interface K8sWorkloadsFilter {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
  kind?: MaybeRefOrGetter<WorkloadKind | undefined>
}

const Q_WORKLOADS = `
  query K8sWorkloads($cluster: UUID!, $namespace: String, $kind: WorkloadKind) {
    kubernetes {
      workloads(cluster: $cluster, namespace: $namespace, kind: $kind) {
        id kind name namespace ready desired status image age cpu memory restarts strategy
      }
    }
  }
`

const KIND_TO_GQL: Record<WorkloadKind, string> = {
  Deployment: 'DEPLOYMENT',
  StatefulSet: 'STATEFUL_SET',
  DaemonSet: 'DAEMON_SET',
  ReplicaSet: 'REPLICA_SET',
  Job: 'JOB',
  CronJob: 'CRON_JOB',
}

export function useK8sWorkloads(filter: K8sWorkloadsFilter) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { workloads: GqlWorkload[] } }>(
    'k8s.workloads',
    Q_WORKLOADS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
      kind: computed(() => {
        const k = toValue(filter.kind)
        return k ? KIND_TO_GQL[k] : null
      }),
    },
  )
  return mapped<{ kubernetes: { workloads: GqlWorkload[] } }, K8sWorkload[]>(
    result,
    d => (d?.kubernetes?.workloads ?? []).map(toMockWorkload),
  )
}

// Pods ------------------------------------------------------------------

export interface K8sPodsFilter {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
  workloadId?: MaybeRefOrGetter<string | null | undefined>
  search?: MaybeRefOrGetter<string | undefined>
  limit?: MaybeRefOrGetter<number | undefined>
  offset?: MaybeRefOrGetter<number | undefined>
}

export interface K8sPodPage {
  total: number
  items: K8sPodWithWorkload[]
}

const Q_PODS = `
  query K8sPods(
    $cluster: UUID!,
    $namespace: String,
    $workloadId: ID,
    $search: String,
    $limit: Int,
    $offset: Int,
  ) {
    kubernetes {
      pods(
        cluster: $cluster,
        namespace: $namespace,
        workloadId: $workloadId,
        search: $search,
        limit: $limit,
        offset: $offset,
      ) {
        total
        items {
          id name namespace node status ready restarts age cpu memory
          image workloadKind workloadName
          workload {
            id kind name namespace ready desired status image age cpu memory restarts strategy
          }
        }
      }
    }
  }
`

const PHANTOM_WORKLOAD: K8sWorkload = {
  id: '',
  kind: 'Deployment',
  name: '',
  ns: '',
  replicas: '',
  ready: 0,
  want: 0,
  status: 'warn',
  image: '',
  age: '',
  cpu: 0,
  mem: 0,
  restarts: 0,
  strategy: '',
}

export function useK8sPods(filter: K8sPodsFilter) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { pods: { total: number; items: GqlPod[] } } }>(
    'k8s.pods',
    Q_PODS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
      workloadId: computed(() => toValue(filter.workloadId) ?? null),
      search: computed(() => toValue(filter.search) ?? null),
      limit: computed(() => toValue(filter.limit) ?? null),
      offset: computed(() => toValue(filter.offset) ?? null),
    },
  )
  return mapped<{ kubernetes: { pods: { total: number; items: GqlPod[] } } }, K8sPodPage>(
    result,
    (d) => {
      const page = d?.kubernetes?.pods
      if (!page) return { total: 0, items: [] }
      return {
        total: page.total,
        items: page.items.map(p => toMockPod(p, { ...PHANTOM_WORKLOAD, ns: p.namespace, name: p.name })),
      }
    },
  )
}

// Nodes -----------------------------------------------------------------

const Q_NODES = `
  query K8sNodes($cluster: UUID!) {
    kubernetes {
      nodes(cluster: $cluster) {
        name role instance zone status cpu memory pods age version taints labels
      }
    }
  }
`

export function useK8sNodes(cluster: MaybeRefOrGetter<string | null | undefined>) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { nodes: GqlNode[] } }>(
    'k8s.nodes',
    Q_NODES,
    { cluster: computed(() => toValue(cluster)) },
  )
  return mapped<{ kubernetes: { nodes: GqlNode[] } }, K8sNode[]>(
    result,
    d => (d?.kubernetes?.nodes ?? []).map(toMockNode),
  )
}

// Events ----------------------------------------------------------------

export interface K8sEventsFilter {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
  level?: MaybeRefOrGetter<EventLevel | undefined>
  limit?: MaybeRefOrGetter<number | undefined>
}

const LEVEL_TO_GQL: Record<EventLevel, string> = {
  info: 'INFO',
  warn: 'WARN',
  error: 'ERROR',
}

const Q_EVENTS = `
  query K8sEvents($cluster: UUID!, $namespace: String, $level: EventLevel, $limit: Int) {
    kubernetes {
      events(cluster: $cluster, namespace: $namespace, level: $level, limit: $limit) {
        id level when timestamp namespace involvedObject message reason
      }
    }
  }
`

export function useK8sEvents(filter: K8sEventsFilter) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { events: GqlEvent[] } }>(
    'k8s.events',
    Q_EVENTS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
      level: computed(() => {
        const l = toValue(filter.level)
        return l ? LEVEL_TO_GQL[l] : null
      }),
      limit: computed(() => toValue(filter.limit) ?? null),
    },
  )
  return mapped<{ kubernetes: { events: GqlEvent[] } }, K8sEvent[]>(
    result,
    d => (d?.kubernetes?.events ?? []).map(toMockEvent),
  )
}

// Namespaces ------------------------------------------------------------

const Q_NAMESPACES = `
  query K8sNamespaces($cluster: UUID!) {
    kubernetes {
      namespaces(cluster: $cluster) {
        name status workloads pods services age
      }
    }
  }
`

export function useK8sNamespaces(cluster: MaybeRefOrGetter<string | null | undefined>) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { namespaces: GqlNamespace[] } }>(
    'k8s.namespaces',
    Q_NAMESPACES,
    { cluster: computed(() => toValue(cluster)) },
  )
  return mapped<{ kubernetes: { namespaces: GqlNamespace[] } }, K8sNamespaceCard[]>(
    result,
    d => (d?.kubernetes?.namespaces ?? []).map(n => ({
      name: n.name,
      status: n.status,
      workloads: n.workloads,
      pods: n.pods,
      services: n.services,
      age: n.age,
    })),
  )
}

// Services / Ingresses / Network Policies -------------------------------

export interface K8sNamespaceScoped {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
}

const Q_SERVICES = `
  query K8sServices($cluster: UUID!, $namespace: String) {
    kubernetes {
      services(cluster: $cluster, namespace: $namespace) {
        id name namespace type clusterIP externalIP ports selector age endpoints
      }
    }
  }
`

export function useK8sServices(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { services: GqlService[] } }>(
    'k8s.services',
    Q_SERVICES,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { services: GqlService[] } }, K8sService[]>(
    result,
    d => (d?.kubernetes?.services ?? []).map(toMockService),
  )
}

const Q_INGRESSES = `
  query K8sIngresses($cluster: UUID!, $namespace: String) {
    kubernetes {
      ingresses(cluster: $cluster, namespace: $namespace) {
        id name namespace ingressClass hosts paths backends tls age
      }
    }
  }
`

export function useK8sIngresses(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { ingresses: GqlIngress[] } }>(
    'k8s.ingresses',
    Q_INGRESSES,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { ingresses: GqlIngress[] } }, K8sIngress[]>(
    result,
    d => (d?.kubernetes?.ingresses ?? []).map(toMockIngress),
  )
}

const Q_NETPOLS = `
  query K8sNetworkPolicies($cluster: UUID!, $namespace: String) {
    kubernetes {
      networkPolicies(cluster: $cluster, namespace: $namespace) {
        id name namespace podSelector ingress egress age
      }
    }
  }
`

export function useK8sNetworkPolicies(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { networkPolicies: GqlNetworkPolicy[] } }>(
    'k8s.networkPolicies',
    Q_NETPOLS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { networkPolicies: GqlNetworkPolicy[] } }, K8sNetworkPolicy[]>(
    result,
    d => (d?.kubernetes?.networkPolicies ?? []).map(toMockNetworkPolicy),
  )
}

// Gateway API -----------------------------------------------------------

const Q_GATEWAY_CLASSES = `
  query K8sGatewayClasses($cluster: UUID!) {
    kubernetes {
      gatewayClasses(cluster: $cluster) {
        name controller accepted age
      }
    }
  }
`

export function useK8sGatewayClasses(cluster: MaybeRefOrGetter<string | null | undefined>) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { gatewayClasses: GqlGatewayClass[] } }>(
    'k8s.gatewayClasses',
    Q_GATEWAY_CLASSES,
    { cluster: computed(() => toValue(cluster)) },
  )
  return mapped<{ kubernetes: { gatewayClasses: GqlGatewayClass[] } }, K8sGatewayClass[]>(
    result,
    d => (d?.kubernetes?.gatewayClasses ?? []).map(toMockGatewayClass),
  )
}

const Q_GATEWAYS = `
  query K8sGateways($cluster: UUID!, $namespace: String) {
    kubernetes {
      gateways(cluster: $cluster, namespace: $namespace) {
        id name namespace gatewayClass addresses listeners routes status age
      }
    }
  }
`

export function useK8sGateways(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { gateways: GqlGateway[] } }>(
    'k8s.gateways',
    Q_GATEWAYS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { gateways: GqlGateway[] } }, K8sGateway[]>(
    result,
    d => (d?.kubernetes?.gateways ?? []).map(toMockGateway),
  )
}

const Q_HTTP_ROUTES = `
  query K8sHttpRoutes($cluster: UUID!, $namespace: String) {
    kubernetes {
      httpRoutes(cluster: $cluster, namespace: $namespace) {
        id name namespace parents hosts paths backends rules age status
      }
    }
  }
`

export function useK8sHttpRoutes(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { httpRoutes: GqlHttpRoute[] } }>(
    'k8s.httpRoutes',
    Q_HTTP_ROUTES,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { httpRoutes: GqlHttpRoute[] } }, K8sHttpRoute[]>(
    result,
    d => (d?.kubernetes?.httpRoutes ?? []).map(toMockHttpRoute),
  )
}

// Config & Secrets ------------------------------------------------------

export interface K8sConfigResourcesFilter extends K8sNamespaceScoped {
  kind?: MaybeRefOrGetter<ConfigKind | undefined>
}

const CONFIG_KIND_TO_GQL: Record<ConfigKind, string> = {
  ConfigMap: 'CONFIG_MAP',
  Secret: 'SECRET',
}

const Q_CONFIG_RESOURCES = `
  query K8sConfigResources($cluster: UUID!, $namespace: String, $kind: ConfigKind) {
    kubernetes {
      configResources(cluster: $cluster, namespace: $namespace, kind: $kind) {
        id kind name namespace keys size age secretType managedBy
      }
    }
  }
`

export function useK8sConfigResources(filter: K8sConfigResourcesFilter) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { configResources: GqlConfigResource[] } }>(
    'k8s.configResources',
    Q_CONFIG_RESOURCES,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
      kind: computed(() => {
        const k = toValue(filter.kind)
        return k ? CONFIG_KIND_TO_GQL[k] : null
      }),
    },
  )
  return mapped<{ kubernetes: { configResources: GqlConfigResource[] } }, K8sConfigResource[]>(
    result,
    d => (d?.kubernetes?.configResources ?? []).map(toMockConfigResource),
  )
}

const Q_CONFIG_ENTRIES = `
  query K8sConfigEntries($cluster: UUID!, $namespace: String!, $kind: ConfigKind!, $name: String!) {
    kubernetes {
      configEntries(cluster: $cluster, namespace: $namespace, kind: $kind, name: $name) {
        key value
      }
    }
  }
`

export interface K8sConfigEntriesFilter {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace: MaybeRefOrGetter<string | null | undefined>
  kind: MaybeRefOrGetter<ConfigKind | null | undefined>
  name: MaybeRefOrGetter<string | null | undefined>
}

export function useK8sConfigEntries(filter: K8sConfigEntriesFilter) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { configEntries: GqlConfigEntry[] } }>(
    'k8s.configEntries',
    Q_CONFIG_ENTRIES,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace)),
      kind: computed(() => {
        const k = toValue(filter.kind)
        return k ? CONFIG_KIND_TO_GQL[k] : null
      }),
      name: computed(() => toValue(filter.name)),
    },
  )
  return mapped<{ kubernetes: { configEntries: GqlConfigEntry[] } }, GqlConfigEntry[]>(
    result,
    d => d?.kubernetes?.configEntries ?? [],
  )
}

// Storage ---------------------------------------------------------------

const Q_STORAGE_CLASSES = `
  query K8sStorageClasses($cluster: UUID!) {
    kubernetes {
      storageClasses(cluster: $cluster) {
        name provisioner reclaim binding isDefault age parameters
      }
    }
  }
`

export function useK8sStorageClasses(cluster: MaybeRefOrGetter<string | null | undefined>) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { storageClasses: GqlStorageClass[] } }>(
    'k8s.storageClasses',
    Q_STORAGE_CLASSES,
    { cluster: computed(() => toValue(cluster)) },
  )
  return mapped<{ kubernetes: { storageClasses: GqlStorageClass[] } }, K8sStorageClass[]>(
    result,
    d => (d?.kubernetes?.storageClasses ?? []).map(toMockStorageClass),
  )
}

const Q_PVCS = `
  query K8sPvcs($cluster: UUID!, $namespace: String) {
    kubernetes {
      pvcs(cluster: $cluster, namespace: $namespace) {
        id name namespace status volume capacity accessMode storageClass age usedPercent workload
      }
    }
  }
`

export function useK8sPvcs(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { pvcs: GqlPvc[] } }>(
    'k8s.pvcs',
    Q_PVCS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { pvcs: GqlPvc[] } }, K8sPVC[]>(
    result,
    d => (d?.kubernetes?.pvcs ?? []).map(toMockPvc),
  )
}

// Autoscaling & disruption budgets --------------------------------------

const Q_HPAS = `
  query K8sHpas($cluster: UUID!, $namespace: String) {
    kubernetes {
      horizontalPodAutoscalers(cluster: $cluster, namespace: $namespace) {
        id name namespace targetKind targetName minReplicas maxReplicas
        currentReplicas desiredReplicas ableToScale scalingActive scalingLimited
        lastScaleTime age
        metrics { label current target }
      }
    }
  }
`

interface GqlHpa {
  id: string
  name: string
  namespace: string
  targetKind: string
  targetName: string
  minReplicas: number
  maxReplicas: number
  currentReplicas: number
  desiredReplicas: number
  metrics: K8sHpaMetric[]
  ableToScale: boolean
  scalingActive: boolean
  scalingLimited: boolean
  lastScaleTime: string | null
  age: string
}

function toMockHpa(h: GqlHpa): K8sHpa {
  return {
    id: h.id,
    name: h.name,
    ns: h.namespace,
    targetKind: h.targetKind,
    targetName: h.targetName,
    minReplicas: h.minReplicas,
    maxReplicas: h.maxReplicas,
    currentReplicas: h.currentReplicas,
    desiredReplicas: h.desiredReplicas,
    metrics: h.metrics,
    ableToScale: h.ableToScale,
    scalingActive: h.scalingActive,
    scalingLimited: h.scalingLimited,
    lastScaleTime: h.lastScaleTime,
    age: h.age,
  }
}

export function useK8sHpas(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { horizontalPodAutoscalers: GqlHpa[] } }>(
    'k8s.hpas',
    Q_HPAS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { horizontalPodAutoscalers: GqlHpa[] } }, K8sHpa[]>(
    result,
    d => (d?.kubernetes?.horizontalPodAutoscalers ?? []).map(toMockHpa),
  )
}

const Q_PDBS = `
  query K8sPdbs($cluster: UUID!, $namespace: String) {
    kubernetes {
      podDisruptionBudgets(cluster: $cluster, namespace: $namespace) {
        id name namespace minAvailable maxUnavailable currentHealthy
        desiredHealthy disruptionsAllowed expectedPods selector age
      }
    }
  }
`

interface GqlPdb {
  id: string
  name: string
  namespace: string
  minAvailable: string | null
  maxUnavailable: string | null
  currentHealthy: number
  desiredHealthy: number
  disruptionsAllowed: number
  expectedPods: number
  selector: string
  age: string
}

function toMockPdb(p: GqlPdb): K8sPdb {
  return {
    id: p.id,
    name: p.name,
    ns: p.namespace,
    minAvailable: p.minAvailable,
    maxUnavailable: p.maxUnavailable,
    currentHealthy: p.currentHealthy,
    desiredHealthy: p.desiredHealthy,
    disruptionsAllowed: p.disruptionsAllowed,
    expectedPods: p.expectedPods,
    selector: p.selector,
    age: p.age,
  }
}

export function useK8sPdbs(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { podDisruptionBudgets: GqlPdb[] } }>(
    'k8s.pdbs',
    Q_PDBS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { podDisruptionBudgets: GqlPdb[] } }, K8sPdb[]>(
    result,
    d => (d?.kubernetes?.podDisruptionBudgets ?? []).map(toMockPdb),
  )
}

// CRDs & Operators ------------------------------------------------------

const Q_OPERATORS = `
  query K8sOperators($cluster: UUID!) {
    kubernetes {
      operators(cluster: $cluster) {
        id name version group namespace status instances kinds description
      }
    }
  }
`

export function useK8sOperators(cluster: MaybeRefOrGetter<string | null | undefined>) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { operators: GqlOperator[] } }>(
    'k8s.operators',
    Q_OPERATORS,
    { cluster: computed(() => toValue(cluster)) },
  )
  return mapped<{ kubernetes: { operators: GqlOperator[] } }, K8sOperator[]>(
    result,
    d => (d?.kubernetes?.operators ?? []).map(toMockOperator),
  )
}

export interface K8sCustomResourcesFilter {
  cluster: MaybeRefOrGetter<string | null | undefined>
  group?: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
}

const Q_CUSTOM_RESOURCES = `
  query K8sCustomResources($cluster: UUID!, $group: String, $namespace: String) {
    kubernetes {
      customResources(cluster: $cluster, group: $group, namespace: $namespace) {
        id kind group version namespace name age status detail
      }
    }
  }
`

export function useK8sCustomResources(filter: K8sCustomResourcesFilter) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { customResources: GqlCustomResource[] } }>(
    'k8s.customResources',
    Q_CUSTOM_RESOURCES,
    {
      cluster: computed(() => toValue(filter.cluster)),
      group: computed(() => toValue(filter.group) ?? null),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { customResources: GqlCustomResource[] } }, K8sCustomResource[]>(
    result,
    d => (d?.kubernetes?.customResources ?? []).map(toMockCustomResource),
  )
}

// RBAC ------------------------------------------------------------------

export interface K8sRolesFilter {
  cluster: MaybeRefOrGetter<string | null | undefined>
  kind?: MaybeRefOrGetter<string | null | undefined>
}

const Q_ROLES = `
  query K8sRoles($cluster: UUID!, $kind: String) {
    kubernetes {
      roles(cluster: $cluster, kind: $kind) {
        id kind name builtin bindings rules age namespace description
      }
    }
  }
`

export function useK8sRoles(filter: K8sRolesFilter) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { roles: GqlRole[] } }>(
    'k8s.roles',
    Q_ROLES,
    {
      cluster: computed(() => toValue(filter.cluster)),
      kind: computed(() => toValue(filter.kind) ?? null),
    },
  )
  return mapped<{ kubernetes: { roles: GqlRole[] } }, K8sRole[]>(
    result,
    d => (d?.kubernetes?.roles ?? []).map(toMockRole),
  )
}

const Q_ROLE_BINDINGS = `
  query K8sRoleBindings($cluster: UUID!, $kind: String) {
    kubernetes {
      roleBindings(cluster: $cluster, kind: $kind) {
        id kind name role namespace age
        subjects { kind name namespace }
      }
    }
  }
`

export function useK8sRoleBindings(filter: K8sRolesFilter) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { roleBindings: GqlRoleBinding[] } }>(
    'k8s.roleBindings',
    Q_ROLE_BINDINGS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      kind: computed(() => toValue(filter.kind) ?? null),
    },
  )
  return mapped<{ kubernetes: { roleBindings: GqlRoleBinding[] } }, K8sRoleBinding[]>(
    result,
    d => (d?.kubernetes?.roleBindings ?? []).map(toMockRoleBinding),
  )
}

const Q_SERVICE_ACCOUNTS = `
  query K8sServiceAccounts($cluster: UUID!, $namespace: String) {
    kubernetes {
      serviceAccounts(cluster: $cluster, namespace: $namespace) {
        id name namespace pods secrets bindings age iamRole
      }
    }
  }
`

export function useK8sServiceAccounts(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { serviceAccounts: GqlServiceAccount[] } }>(
    'k8s.serviceAccounts',
    Q_SERVICE_ACCOUNTS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { serviceAccounts: GqlServiceAccount[] } }, K8sServiceAccount[]>(
    result,
    d => (d?.kubernetes?.serviceAccounts ?? []).map(toMockServiceAccount),
  )
}

// cert-manager ----------------------------------------------------------

const Q_CERTS = `
  query K8sCertificates($cluster: UUID!, $namespace: String) {
    kubernetes {
      certManagerCertificates(cluster: $cluster, namespace: $namespace) {
        id name namespace dns issuer status readySince expiresInDays renewsInDays secretName error
      }
    }
  }
`

export function useK8sCertificates(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { certManagerCertificates: GqlCertificate[] } }>(
    'k8s.certs',
    Q_CERTS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { certManagerCertificates: GqlCertificate[] } }, K8sCertificate[]>(
    result,
    d => (d?.kubernetes?.certManagerCertificates ?? []).map(toMockCertificate),
  )
}

const Q_ISSUERS = `
  query K8sIssuers($cluster: UUID!, $namespace: String) {
    kubernetes {
      certManagerIssuers(cluster: $cluster, namespace: $namespace) {
        id kind name type server status age certs namespace
      }
    }
  }
`

export function useK8sIssuers(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { certManagerIssuers: GqlIssuer[] } }>(
    'k8s.issuers',
    Q_ISSUERS,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { certManagerIssuers: GqlIssuer[] } }, K8sIssuer[]>(
    result,
    d => (d?.kubernetes?.certManagerIssuers ?? []).map(toMockIssuer),
  )
}

// CloudNativePG ---------------------------------------------------------

const Q_CNPG = `
  query K8sCnpgClusters($cluster: UUID!, $namespace: String) {
    kubernetes {
      cnpgClusters(cluster: $cluster, namespace: $namespace) {
        name namespace instances primary postgresVersion image
        status statusKind age backupSchedule lastBackup
      }
    }
  }
`

export function useK8sCnpgClusters(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { cnpgClusters: GqlCnpgCluster[] } }>(
    'k8s.cnpgClusters',
    Q_CNPG,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { cnpgClusters: GqlCnpgCluster[] } }, K8sCnpgCluster[]>(
    result,
    d => (d?.kubernetes?.cnpgClusters ?? []).map(toMockCnpgCluster),
  )
}

export interface K8sCnpgClusterIdent {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace: MaybeRefOrGetter<string | null | undefined>
  name: MaybeRefOrGetter<string | null | undefined>
}

const Q_CNPG_DETAIL = `
  query K8sCnpgCluster($cluster: UUID!, $namespace: String!, $name: String!) {
    kubernetes {
      cnpgCluster(cluster: $cluster, namespace: $namespace, name: $name) {
        cluster {
          name namespace instances primary postgresVersion image
          status statusKind age backupSchedule lastBackup
        }
        storageSize storageClass backupDestinationPath backupRetention
        instances { name role status ready node zone pvcSize restarts age }
        backups { name method phase statusKind started completed duration }
        parameters { key value }
      }
    }
  }
`

/**
 * Full detail for a single CNPG cluster. `namespace` and `name`
 * resolve to `undefined` (not `null`) until known, so the query waits
 * for them — both are required (`String!`) on the server. Returns
 * `null` when the named cluster is absent.
 */
export function useK8sCnpgCluster(ident: K8sCnpgClusterIdent) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { cnpgCluster: GqlCnpgClusterDetail | null } }>(
    'k8s.cnpgCluster',
    Q_CNPG_DETAIL,
    {
      cluster: computed(() => toValue(ident.cluster) ?? undefined),
      namespace: computed(() => toValue(ident.namespace) ?? undefined),
      name: computed(() => toValue(ident.name) ?? undefined),
    },
  )
  return mapped<{ kubernetes: { cnpgCluster: GqlCnpgClusterDetail | null } }, K8sCnpgClusterDetail | null>(
    result,
    d => {
      const detail = d?.kubernetes?.cnpgCluster
      return detail ? toCnpgClusterDetail(detail) : null
    },
  )
}

// Helm ------------------------------------------------------------------

const Q_HELM_REPOS = `
  query K8sHelmRepos {
    kubernetes {
      helmRepos { name url type charts lastUpdate }
    }
  }
`

export function useK8sHelmRepos() {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { helmRepos: GqlHelmRepo[] } }>(
    'k8s.helmRepos',
    Q_HELM_REPOS,
  )
  return mapped<{ kubernetes: { helmRepos: GqlHelmRepo[] } }, K8sHelmRepo[]>(
    result,
    d => (d?.kubernetes?.helmRepos ?? []).map(toMockHelmRepo),
  )
}

export interface K8sHelmChartsFilter {
  repo?: MaybeRefOrGetter<string | null | undefined>
  search?: MaybeRefOrGetter<string | null | undefined>
}

const Q_HELM_CHARTS = `
  query K8sHelmCharts($repo: String, $search: String) {
    kubernetes {
      helmCharts(repo: $repo, search: $search) {
        id name repo version appVersion description icon
      }
    }
  }
`

export function useK8sHelmCharts(filter: K8sHelmChartsFilter = {}) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { helmCharts: GqlHelmChart[] } }>(
    'k8s.helmCharts',
    Q_HELM_CHARTS,
    {
      repo: computed(() => toValue(filter.repo) ?? null),
      search: computed(() => toValue(filter.search) ?? null),
    },
  )
  return mapped<{ kubernetes: { helmCharts: GqlHelmChart[] } }, K8sHelmChart[]>(
    result,
    d => (d?.kubernetes?.helmCharts ?? []).map(toMockHelmChart),
  )
}

export interface K8sHelmChartIdent {
  repo: MaybeRefOrGetter<string | null | undefined>
  chart: MaybeRefOrGetter<string | null | undefined>
}

const Q_HELM_CHART_VERSIONS = `
  query K8sHelmChartVersions($repo: String!, $chart: String!) {
    kubernetes {
      helmChartVersions(repo: $repo, chart: $chart) {
        version appVersion released current
      }
    }
  }
`

export function useK8sHelmChartVersions(ident: K8sHelmChartIdent) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { helmChartVersions: K8sHelmChartVersionRow[] } }>(
    'k8s.helmChartVersions',
    Q_HELM_CHART_VERSIONS,
    {
      repo: computed(() => toValue(ident.repo)),
      chart: computed(() => toValue(ident.chart)),
    },
  )
  return mapped<{ kubernetes: { helmChartVersions: K8sHelmChartVersionRow[] } }, K8sHelmChartVersionRow[]>(
    result,
    d => d?.kubernetes?.helmChartVersions ?? [],
  )
}

export interface K8sHelmChartValuesArgs extends K8sHelmChartIdent {
  version: MaybeRefOrGetter<string | null | undefined>
}

const Q_HELM_CHART_VALUES = `
  query K8sHelmChartValues($repo: String!, $chart: String!, $version: String!) {
    kubernetes {
      helmChartValues(repo: $repo, chart: $chart, version: $version) {
        defaultValues schema
      }
    }
  }
`

export function useK8sHelmChartValues(args: K8sHelmChartValuesArgs) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { helmChartValues: K8sHelmChartValuesRow } }>(
    'k8s.helmChartValues',
    Q_HELM_CHART_VALUES,
    {
      repo: computed(() => toValue(args.repo)),
      chart: computed(() => toValue(args.chart)),
      version: computed(() => toValue(args.version)),
    },
  )
  return mapped<{ kubernetes: { helmChartValues: K8sHelmChartValuesRow } }, K8sHelmChartValuesRow | null>(
    result,
    d => d?.kubernetes?.helmChartValues ?? null,
  )
}

const Q_HELM_RELEASES = `
  query K8sHelmReleases($cluster: UUID!, $namespace: String) {
    kubernetes {
      helmReleases(cluster: $cluster, namespace: $namespace) {
        id name namespace chart chartVersion appVersion revision status
        updated installed repo repoUrl description
      }
    }
  }
`

export function useK8sHelmReleases(filter: K8sNamespaceScoped) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { helmReleases: GqlHelmRelease[] } }>(
    'k8s.helmReleases',
    Q_HELM_RELEASES,
    {
      cluster: computed(() => toValue(filter.cluster)),
      namespace: computed(() => toValue(filter.namespace) ?? null),
    },
  )
  return mapped<{ kubernetes: { helmReleases: GqlHelmRelease[] } }, K8sHelmRelease[]>(
    result,
    d => (d?.kubernetes?.helmReleases ?? []).map(toMockHelmRelease),
  )
}

export interface K8sHelmReleaseIdent {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace: MaybeRefOrGetter<string | null | undefined>
  name: MaybeRefOrGetter<string | null | undefined>
}

const Q_HELM_RELEASE_HISTORY = `
  query K8sHelmReleaseHistory($cluster: UUID!, $namespace: String!, $name: String!) {
    kubernetes {
      helmReleaseHistory(cluster: $cluster, namespace: $namespace, name: $name) {
        revision updated status chart appVersion description
      }
    }
  }
`

interface GqlHelmRevision {
  revision: number
  updated: string
  status: string
  chart: string
  appVersion: string
  description: string
}

export function useK8sHelmReleaseHistory(ident: K8sHelmReleaseIdent) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { helmReleaseHistory: GqlHelmRevision[] } }>(
    'k8s.helmReleaseHistory',
    Q_HELM_RELEASE_HISTORY,
    {
      cluster: computed(() => toValue(ident.cluster)),
      namespace: computed(() => toValue(ident.namespace)),
      name: computed(() => toValue(ident.name)),
    },
  )
  return mapped<{ kubernetes: { helmReleaseHistory: GqlHelmRevision[] } }, K8sHelmRevisionRow[]>(
    result,
    d => (d?.kubernetes?.helmReleaseHistory ?? []).map(r => ({
      revision: r.revision,
      updated: r.updated,
      status: HELM_STATUS_MAP[r.status] ?? 'pending',
      chart: r.chart,
      appVersion: r.appVersion,
      description: r.description,
    })),
  )
}

export interface K8sHelmReleaseTextIdent extends K8sHelmReleaseIdent {
  revision?: MaybeRefOrGetter<number | null | undefined>
}

const Q_HELM_RELEASE_VALUES = `
  query K8sHelmReleaseValues($cluster: UUID!, $namespace: String!, $name: String!, $revision: Int) {
    kubernetes {
      helmReleaseValues(cluster: $cluster, namespace: $namespace, name: $name, revision: $revision)
    }
  }
`

export function useK8sHelmReleaseValues(ident: K8sHelmReleaseTextIdent) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { helmReleaseValues: string } }>(
    'k8s.helmReleaseValues',
    Q_HELM_RELEASE_VALUES,
    {
      cluster: computed(() => toValue(ident.cluster)),
      namespace: computed(() => toValue(ident.namespace)),
      name: computed(() => toValue(ident.name)),
      revision: computed(() => toValue(ident.revision) ?? null),
    },
  )
  return mapped<{ kubernetes: { helmReleaseValues: string } }, string>(
    result,
    d => d?.kubernetes?.helmReleaseValues ?? '',
  )
}

const Q_HELM_RELEASE_MANIFEST = `
  query K8sHelmReleaseManifest($cluster: UUID!, $namespace: String!, $name: String!, $revision: Int) {
    kubernetes {
      helmReleaseManifest(cluster: $cluster, namespace: $namespace, name: $name, revision: $revision)
    }
  }
`

export function useK8sHelmReleaseManifest(ident: K8sHelmReleaseTextIdent) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { helmReleaseManifest: string } }>(
    'k8s.helmReleaseManifest',
    Q_HELM_RELEASE_MANIFEST,
    {
      cluster: computed(() => toValue(ident.cluster)),
      namespace: computed(() => toValue(ident.namespace)),
      name: computed(() => toValue(ident.name)),
      revision: computed(() => toValue(ident.revision) ?? null),
    },
  )
  return mapped<{ kubernetes: { helmReleaseManifest: string } }, string>(
    result,
    d => d?.kubernetes?.helmReleaseManifest ?? '',
  )
}

// Raw YAML for a single resource ---------------------------------------

export interface K8sResourceYamlIdent {
  cluster: MaybeRefOrGetter<string | null | undefined>
  namespace?: MaybeRefOrGetter<string | null | undefined>
  kind: MaybeRefOrGetter<string | null | undefined>
  group?: MaybeRefOrGetter<string | null | undefined>
  name: MaybeRefOrGetter<string | null | undefined>
}

const Q_YAML = `
  query K8sResourceYaml($cluster: UUID!, $namespace: String, $kind: String!, $group: String, $name: String!) {
    kubernetes {
      yaml(cluster: $cluster, namespace: $namespace, kind: $kind, group: $group, name: $name)
    }
  }
`

export function useK8sResourceYaml(ident: K8sResourceYamlIdent) {
  const { useAsyncQuery } = useGraphQL()
  const result = useAsyncQuery<{ kubernetes: { yaml: string } }>(
    'k8s.yaml',
    Q_YAML,
    {
      cluster: computed(() => toValue(ident.cluster)),
      namespace: computed(() => toValue(ident.namespace) ?? null),
      kind: computed(() => toValue(ident.kind)),
      group: computed(() => toValue(ident.group) ?? null),
      name: computed(() => toValue(ident.name)),
    },
  )
  return mapped<{ kubernetes: { yaml: string } }, string>(
    result,
    d => d?.kubernetes?.yaml ?? '',
  )
}

// ---------------------------------------------------------------------
// Misc
// ---------------------------------------------------------------------

export function k8sCount<T>(data: { value: T[] | null | undefined }): ComputedRef<number> {
  return computed(() => data.value?.length ?? 0)
}
