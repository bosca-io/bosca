// Wire-shape types for the Kubernetes subsystem. Consumed by:
//   * pages and components — for prop typing and array shapes
//   * useK8sQueries — as the return type of every GraphQL composable
//
// The shapes mirror what `bosca-server` returns over GraphQL (which in
// turn mirrors what `kubernetes-controller` emits over its internal
// HTTP API). When the backend grows a field, add it here and the adapter
// layer in useK8sQueries picks it up automatically.

export type ClusterHealth = 'ok' | 'warn' | 'err'
export type ClusterEnv = 'production' | 'staging' | 'dev'
export type WorkloadKind = 'Deployment' | 'StatefulSet' | 'DaemonSet' | 'ReplicaSet' | 'Job' | 'CronJob'
export type WorkloadStatus = 'ok' | 'pending' | 'warn' | 'err'
export type NodeRole = 'control-plane' | 'worker'
export type NodeStatus = 'Ready' | 'NotReady' | 'DiskPressure' | 'MemoryPressure'
export type EventLevel = 'info' | 'warn' | 'error'

export interface K8sCluster {
  [key: string]: unknown
  id: string
  name: string
  provider: string
  region: string
  env: ClusterEnv
  health: ClusterHealth
  version: string
  nodes: number
  pods: number
}

export interface K8sWorkload {
  [key: string]: unknown
  id: string
  kind: WorkloadKind
  name: string
  ns: string
  replicas: string
  ready: number
  want: number
  status: WorkloadStatus
  image: string
  age: string
  cpu: number
  mem: number
  restarts: number
  strategy: string
}

export interface K8sPod {
  name: string
  node: string
  status: string
  ready: string
  restarts: number
  age: string
  cpu: number
  mem: number
}

export interface K8sPodWithWorkload extends K8sPod {
  workload: K8sWorkload
}

export interface K8sNode {
  [key: string]: unknown
  name: string
  role: NodeRole
  instance: string
  zone: string
  status: NodeStatus
  cpu: number
  mem: number
  pods: number
  age: string
  version: string
  taints: string[]
  // Raw node labels from the cluster. Used to derive node-pool
  // membership (Karpenter / managed node-group labels) in the
  // capacity-by-pool view. Empty for nodes that carry no labels.
  labels: Record<string, string>
}

export interface K8sEvent {
  id: number
  lvl: EventLevel
  when: string
  ns: string
  obj: string
  msg: string
  reason: string
}

export interface K8sNamespaceTopology {
  ns: string
  svcs: { name: string; pods: { s: 'ok' | 'warn' | 'err' }[] }[]
}

export type HelmStatus = 'deployed' | 'pending' | 'failed' | 'superseded'
export interface K8sHelmRelease {
  [key: string]: unknown
  id: string
  name: string
  ns: string
  chart: string
  chartVersion: string
  appVersion: string
  revision: number
  status: HelmStatus
  updated: string
  installed: string
  repo: string
  repoUrl: string
  description: string
}

export type ConfigKind = 'ConfigMap' | 'Secret'
export interface K8sConfigResource {
  [key: string]: unknown
  id: string
  kind: ConfigKind
  name: string
  ns: string
  keys: string[]
  size: string
  age: string
  type?: string
  managedBy?: string
}

export interface K8sGatewayClass { name: string; controller: string; accepted: boolean; age: string }
export interface K8sGateway {
  id: string
  name: string
  ns: string
  class: string
  addresses: string[]
  listeners: number
  routes: number
  status: 'ok' | 'warn' | 'err'
  age: string
}
export interface K8sHttpRoute {
  id: string
  name: string
  ns: string
  parents: string[]
  hosts: string[]
  paths: string[]
  backends: string[]
  rules: number
  age: string
  status: 'ok' | 'warn' | 'err'
}
export interface K8sService {
  id: string
  name: string
  ns: string
  type: 'ClusterIP' | 'LoadBalancer' | 'NodePort' | 'ExternalName'
  clusterIP: string
  externalIP: string
  ports: string[]
  selector: string
  age: string
  endpoints: number
}
export interface K8sIngress {
  id: string
  name: string
  ns: string
  class: string
  hosts: string[]
  paths: string[]
  backends: string[]
  tls: boolean
  age: string
}
export interface K8sNetworkPolicy {
  id: string
  name: string
  ns: string
  podSelector: string
  ingress: string
  egress: string
  age: string
}

export interface K8sStorageClass {
  name: string
  provisioner: string
  reclaim: 'Delete' | 'Retain'
  binding: 'Immediate' | 'WaitForFirstConsumer'
  default: boolean
  age: string
  params: string
}
export interface K8sPVC {
  id: string
  name: string
  ns: string
  status: 'Bound' | 'Pending' | 'Resizing' | 'Released'
  volume: string
  capacity: string
  access: 'RWO' | 'RWX' | 'ROX'
  storageClass: string
  age: string
  usedPct: number
  workload: string
}

export interface K8sHpaMetric {
  label: string
  current: string | null
  target: string
}
export interface K8sHpa {
  [key: string]: unknown
  id: string
  name: string
  ns: string
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
export interface K8sPdb {
  [key: string]: unknown
  id: string
  name: string
  ns: string
  minAvailable: string | null
  maxUnavailable: string | null
  currentHealthy: number
  desiredHealthy: number
  disruptionsAllowed: number
  expectedPods: number
  selector: string
  age: string
}

export type CertStatus = 'Ready' | 'Renewing' | 'Failed'
export interface K8sCertificate {
  id: string
  name: string
  ns: string
  dns: string[]
  issuer: string
  status: CertStatus
  readySince: string
  expires: number
  renewsIn: number
  secretName: string
  error?: string
}
export interface K8sIssuer {
  id: string
  kind: 'ClusterIssuer' | 'Issuer'
  name: string
  type: 'ACME' | 'CA' | 'Vault' | 'SelfSigned'
  server: string
  status: 'Ready' | 'Failed'
  age: string
  certs: number
  ns?: string
}

export interface K8sServiceAccount {
  id: string
  name: string
  ns: string
  pods: number
  secrets: number
  bindings: number
  age: string
  irsa?: string
}
export interface K8sRole {
  id: string
  kind: 'ClusterRole' | 'Role'
  name: string
  builtin: boolean
  bindings: number
  rules: number
  age: string
  ns?: string
  desc: string
}
export interface K8sRoleBinding {
  id: string
  kind: 'ClusterRoleBinding' | 'RoleBinding'
  name: string
  role: string
  subjects: { kind: 'User' | 'Group' | 'ServiceAccount'; name: string; ns?: string }[]
  ns?: string
  age: string
}

export interface K8sOperator {
  id: string
  name: string
  version: string
  group: string
  ns: string
  status: 'ok' | 'warn' | 'err'
  instances: number
  crds: string[]
  desc: string
}
export interface K8sCustomResource {
  id: string
  kind: string
  group: string
  version: string
  ns: string
  name: string
  age: string
  status: 'ok' | 'warn' | 'err'
  detail: string
}
export interface K8sCnpgCluster {
  [key: string]: unknown
  name: string
  namespace: string
  instances: number
  primary: string
  pgVersion: string
  image: string
  status: string
  statusKind: 'ok' | 'warn' | 'err'
  age: string
  backupSchedule: string
  lastBackup: string
}
export interface K8sCnpgInstance {
  [key: string]: unknown
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
export interface K8sCnpgBackup {
  [key: string]: unknown
  name: string
  method: string
  phase: string
  statusKind: 'ok' | 'warn' | 'err'
  started: string
  completed: string
  duration: string
}
export interface K8sCnpgParameter {
  key: string
  value: string
}
export interface K8sCnpgClusterDetail {
  cluster: K8sCnpgCluster
  storageSize: string
  storageClass: string
  backupDestinationPath: string
  backupRetention: string
  instances: K8sCnpgInstance[]
  backups: K8sCnpgBackup[]
  parameters: K8sCnpgParameter[]
}
export interface K8sHelmRepo {
  [key: string]: unknown
  name: string
  url: string
  type: 'http' | 'oci'
  charts: number
  lastUpdate: string
}
export interface K8sHelmChart {
  id: string
  name: string
  repo: string
  version: string
  appVersion: string
  description: string
  icon?: string
}
