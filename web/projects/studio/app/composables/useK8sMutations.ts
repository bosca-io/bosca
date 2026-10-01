/**
 * Thin wrappers around the kubernetes GraphQL mutations.
 *
 * Each function returns a typed result and surfaces backend errors to
 * the caller (no toast / no swallow). Pages decide how to render
 * failure — typically a typed-confirm modal that disables on submit
 * and shows an inline error.
 *
 * All mutations require admin group membership at every layer
 * (resolver, HTTP route, fabric8 caller) — the studio UI is allowed to
 * assume that an unauthorized caller will get a clean error back from
 * the GraphQL endpoint and surface it the same way any other backend
 * failure is shown.
 */

import type { Ref } from 'vue'

export type K8sWorkloadKind =
  | 'DEPLOYMENT'
  | 'STATEFUL_SET'
  | 'DAEMON_SET'
  | 'REPLICA_SET'
  | 'JOB'
  | 'CRON_JOB'

export type K8sClusterEnvironment = 'PRODUCTION' | 'STAGING' | 'DEVELOPMENT'

export interface K8sClusterOut {
  id: string
  name: string
  provider: string
  region: string
  environment: K8sClusterEnvironment
  version: string
  health: 'OK' | 'WARN' | 'ERROR'
  nodes: number
  pods: number
}

export interface K8sRegisterClusterInput {
  name: string
  provider: string
  region: string
  environment: K8sClusterEnvironment
  kubeconfig: string
}

export interface K8sUpdateClusterInput {
  name?: string | null
  environment?: K8sClusterEnvironment | null
}

export interface K8sWorkloadOut {
  id: string
  kind: K8sWorkloadKind
  name: string
  namespace: string
  ready: number
  desired: number
  image: string
}

export interface K8sHpaOut {
  id: string
  name: string
  namespace: string
  minReplicas: number
  maxReplicas: number
  currentReplicas: number
  desiredReplicas: number
}

export interface K8sApplyResult {
  succeeded: boolean
  applied: string[]
  failed: Array<{ resource: string; error: string }>
  dryRun?: string | null
}

export interface K8sNamespaceOut {
  name: string
  status: string
}

const REGISTER_CLUSTER = `
  mutation RegisterCluster($input: RegisterClusterInput!) {
    kubernetes {
      registerCluster(input: $input) {
        id name provider region environment version health nodes pods
      }
    }
  }
`

const UPDATE_CLUSTER = `
  mutation UpdateCluster($id: UUID!, $input: UpdateClusterInput!) {
    kubernetes {
      updateCluster(id: $id, input: $input) {
        id name provider region environment version health nodes pods
      }
    }
  }
`

const ROTATE_KUBECONFIG = `
  mutation RotateKubeconfig($id: UUID!, $kubeconfig: String!) {
    kubernetes {
      rotateKubeconfig(id: $id, kubeconfig: $kubeconfig) {
        id name provider region environment version health nodes pods
      }
    }
  }
`

const REMOVE_CLUSTER = `
  mutation RemoveCluster($id: UUID!) {
    kubernetes {
      removeCluster(id: $id)
    }
  }
`

const SCALE_WORKLOAD = `
  mutation ScaleWorkload($cluster: UUID!, $namespace: String!, $kind: WorkloadKind!, $name: String!, $replicas: Int!) {
    kubernetes {
      scaleWorkload(cluster: $cluster, namespace: $namespace, kind: $kind, name: $name, replicas: $replicas) {
        id
        kind
        name
        namespace
        ready
        desired
        image
      }
    }
  }
`

const RESTART_WORKLOAD = `
  mutation RestartWorkload($cluster: UUID!, $namespace: String!, $kind: WorkloadKind!, $name: String!) {
    kubernetes {
      restartWorkload(cluster: $cluster, namespace: $namespace, kind: $kind, name: $name)
    }
  }
`

const UPDATE_HPA_LIMITS = `
  mutation UpdateHpaLimits($cluster: UUID!, $namespace: String!, $name: String!, $minReplicas: Int!, $maxReplicas: Int!) {
    kubernetes {
      updateHpaLimits(cluster: $cluster, namespace: $namespace, name: $name, minReplicas: $minReplicas, maxReplicas: $maxReplicas) {
        id
        name
        namespace
        minReplicas
        maxReplicas
        currentReplicas
        desiredReplicas
      }
    }
  }
`

const DELETE_RESOURCE = `
  mutation DeleteResource($cluster: UUID!, $kind: String!, $name: String!, $namespace: String, $group: String) {
    kubernetes {
      deleteResource(cluster: $cluster, kind: $kind, name: $name, namespace: $namespace, group: $group)
    }
  }
`

const CREATE_NAMESPACE = `
  mutation CreateNamespace($cluster: UUID!, $name: String!, $labels: JSON) {
    kubernetes {
      createNamespace(cluster: $cluster, name: $name, labels: $labels) {
        name
        status
      }
    }
  }
`

const APPLY_MANIFEST = `
  mutation ApplyManifest($cluster: UUID!, $manifest: String!, $dryRun: Boolean) {
    kubernetes {
      applyManifest(cluster: $cluster, manifest: $manifest, dryRun: $dryRun) {
        succeeded
        applied
        failed { resource error }
        dryRun
      }
    }
  }
`

const HELM_REPO_ADD = `
  mutation HelmRepoAdd($name: String!, $url: String!, $username: String, $password: String) {
    kubernetes {
      helmRepoAdd(name: $name, url: $url, username: $username, password: $password) {
        name url type charts lastUpdate
      }
    }
  }
`

const HELM_REPO_UPDATE = `
  mutation HelmRepoUpdate {
    kubernetes {
      helmRepoUpdate { name url type charts lastUpdate }
    }
  }
`

const HELM_REPO_REMOVE = `
  mutation HelmRepoRemove($name: String!) {
    kubernetes { helmRepoRemove(name: $name) }
  }
`

const HELM_INSTALL = `
  mutation HelmInstall($input: HelmInstallInput!) {
    kubernetes {
      helmInstall(input: $input) {
        id name namespace chart chartVersion appVersion revision status
        updated installed repo repoUrl description
      }
    }
  }
`

const HELM_UPGRADE = `
  mutation HelmUpgrade($input: HelmUpgradeInput!) {
    kubernetes {
      helmUpgrade(input: $input) {
        id name namespace chart chartVersion appVersion revision status
        updated installed repo repoUrl description
      }
    }
  }
`

const HELM_ROLLBACK = `
  mutation HelmRollback($cluster: UUID!, $namespace: String!, $name: String!, $toRevision: Int!) {
    kubernetes {
      helmRollback(cluster: $cluster, namespace: $namespace, name: $name, toRevision: $toRevision) {
        id name namespace chart chartVersion appVersion revision status
        updated installed repo repoUrl description
      }
    }
  }
`

const HELM_UNINSTALL = `
  mutation HelmUninstall($cluster: UUID!, $namespace: String!, $name: String!, $keepHistory: Boolean) {
    kubernetes {
      helmUninstall(cluster: $cluster, namespace: $namespace, name: $name, keepHistory: $keepHistory)
    }
  }
`

export interface K8sHelmRepoOut {
  name: string
  url: string
  type: string
  charts: number
  lastUpdate: string
}

export interface K8sHelmReleaseOut {
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

export interface K8sHelmInstallInput {
  name: string
  namespace: string
  createNamespace?: boolean
  repo: string
  chart: string
  version: string
  values?: string | null
  dryRun?: boolean
}

export interface K8sHelmUpgradeInput {
  name: string
  namespace: string
  version: string
  values?: string | null
  dryRun?: boolean
  resetValues?: boolean
}

export interface UseK8sMutationsOptions {
  /** Cluster id resolver. Required at call time — mutations error if absent. */
  cluster: Ref<string | null | undefined>
}

export function useK8sMutations(opts: UseK8sMutationsOptions) {
  const { mutation } = useGraphQL()

  function requireCluster(): string {
    const id = opts.cluster.value
    if (!id) throw new Error('No cluster selected')
    return id
  }

  async function scaleWorkload(args: {
    namespace: string
    kind: K8sWorkloadKind
    name: string
    replicas: number
  }): Promise<K8sWorkloadOut> {
    const data = await mutation<{ kubernetes: { scaleWorkload: K8sWorkloadOut } }>(
      SCALE_WORKLOAD,
      { cluster: requireCluster(), ...args },
    )
    return data.kubernetes.scaleWorkload
  }

  async function restartWorkload(args: {
    namespace: string
    kind: K8sWorkloadKind
    name: string
  }): Promise<boolean> {
    const data = await mutation<{ kubernetes: { restartWorkload: boolean } }>(
      RESTART_WORKLOAD,
      { cluster: requireCluster(), ...args },
    )
    return data.kubernetes.restartWorkload
  }

  async function updateHpaLimits(args: {
    namespace: string
    name: string
    minReplicas: number
    maxReplicas: number
  }): Promise<K8sHpaOut> {
    const data = await mutation<{ kubernetes: { updateHpaLimits: K8sHpaOut } }>(
      UPDATE_HPA_LIMITS,
      { cluster: requireCluster(), ...args },
    )
    return data.kubernetes.updateHpaLimits
  }

  async function deleteResource(args: {
    kind: string
    name: string
    namespace?: string
    group?: string
  }): Promise<boolean> {
    const data = await mutation<{ kubernetes: { deleteResource: boolean } }>(
      DELETE_RESOURCE,
      {
        cluster: requireCluster(),
        kind: args.kind,
        name: args.name,
        namespace: args.namespace ?? null,
        group: args.group ?? null,
      },
    )
    return data.kubernetes.deleteResource
  }

  async function createNamespace(args: {
    name: string
    labels?: Record<string, string> | null
  }): Promise<K8sNamespaceOut> {
    const data = await mutation<{ kubernetes: { createNamespace: K8sNamespaceOut } }>(
      CREATE_NAMESPACE,
      {
        cluster: requireCluster(),
        name: args.name,
        labels: args.labels ?? null,
      },
    )
    return data.kubernetes.createNamespace
  }

  async function applyManifest(args: {
    manifest: string
    dryRun?: boolean
  }): Promise<K8sApplyResult> {
    const data = await mutation<{ kubernetes: { applyManifest: K8sApplyResult } }>(
      APPLY_MANIFEST,
      {
        cluster: requireCluster(),
        manifest: args.manifest,
        dryRun: args.dryRun ?? false,
      },
    )
    return data.kubernetes.applyManifest
  }

  async function helmRepoAdd(args: { name: string; url: string; username?: string | null; password?: string | null }): Promise<K8sHelmRepoOut> {
    const data = await mutation<{ kubernetes: { helmRepoAdd: K8sHelmRepoOut } }>(HELM_REPO_ADD, {
      name: args.name,
      url: args.url,
      username: args.username ?? null,
      password: args.password ?? null,
    })
    return data.kubernetes.helmRepoAdd
  }

  async function helmRepoUpdate(): Promise<K8sHelmRepoOut[]> {
    const data = await mutation<{ kubernetes: { helmRepoUpdate: K8sHelmRepoOut[] } }>(HELM_REPO_UPDATE, {})
    return data.kubernetes.helmRepoUpdate
  }

  async function helmRepoRemove(name: string): Promise<boolean> {
    const data = await mutation<{ kubernetes: { helmRepoRemove: boolean } }>(HELM_REPO_REMOVE, { name })
    return data.kubernetes.helmRepoRemove
  }

  async function helmInstall(input: K8sHelmInstallInput): Promise<K8sHelmReleaseOut> {
    const data = await mutation<{ kubernetes: { helmInstall: K8sHelmReleaseOut } }>(HELM_INSTALL, {
      input: {
        cluster: requireCluster(),
        name: input.name,
        namespace: input.namespace,
        createNamespace: input.createNamespace ?? false,
        repo: input.repo,
        chart: input.chart,
        version: input.version,
        values: input.values ?? null,
        dryRun: input.dryRun ?? false,
      },
    })
    return data.kubernetes.helmInstall
  }

  async function helmUpgrade(input: K8sHelmUpgradeInput): Promise<K8sHelmReleaseOut> {
    const data = await mutation<{ kubernetes: { helmUpgrade: K8sHelmReleaseOut } }>(HELM_UPGRADE, {
      input: {
        cluster: requireCluster(),
        name: input.name,
        namespace: input.namespace,
        version: input.version,
        values: input.values ?? null,
        dryRun: input.dryRun ?? false,
        resetValues: input.resetValues ?? false,
      },
    })
    return data.kubernetes.helmUpgrade
  }

  async function helmRollback(args: { namespace: string; name: string; toRevision: number }): Promise<K8sHelmReleaseOut> {
    const data = await mutation<{ kubernetes: { helmRollback: K8sHelmReleaseOut } }>(HELM_ROLLBACK, {
      cluster: requireCluster(),
      namespace: args.namespace,
      name: args.name,
      toRevision: args.toRevision,
    })
    return data.kubernetes.helmRollback
  }

  async function helmUninstall(args: { namespace: string; name: string; keepHistory?: boolean }): Promise<boolean> {
    const data = await mutation<{ kubernetes: { helmUninstall: boolean } }>(HELM_UNINSTALL, {
      cluster: requireCluster(),
      namespace: args.namespace,
      name: args.name,
      keepHistory: args.keepHistory ?? false,
    })
    return data.kubernetes.helmUninstall
  }

  return {
    scaleWorkload,
    restartWorkload,
    updateHpaLimits,
    deleteResource,
    createNamespace,
    applyManifest,
    helmRepoAdd,
    helmRepoUpdate,
    helmRepoRemove,
    helmInstall,
    helmUpgrade,
    helmRollback,
    helmUninstall,
  }
}

/**
 * Cluster lifecycle mutations. Separate from `useK8sMutations` because
 * registering/removing a cluster is not scoped to an active cluster —
 * the page that uses these operates on the cluster list itself.
 */
export function useK8sClusterMutations() {
  const { mutation } = useGraphQL()

  async function registerCluster(input: K8sRegisterClusterInput): Promise<K8sClusterOut> {
    const data = await mutation<{ kubernetes: { registerCluster: K8sClusterOut } }>(
      REGISTER_CLUSTER,
      { input },
    )
    return data.kubernetes.registerCluster
  }

  async function updateCluster(id: string, input: K8sUpdateClusterInput): Promise<K8sClusterOut> {
    const data = await mutation<{ kubernetes: { updateCluster: K8sClusterOut } }>(
      UPDATE_CLUSTER,
      {
        id,
        input: {
          name: input.name ?? null,
          environment: input.environment ?? null,
        },
      },
    )
    return data.kubernetes.updateCluster
  }

  async function rotateKubeconfig(id: string, kubeconfig: string): Promise<K8sClusterOut> {
    const data = await mutation<{ kubernetes: { rotateKubeconfig: K8sClusterOut } }>(
      ROTATE_KUBECONFIG,
      { id, kubeconfig },
    )
    return data.kubernetes.rotateKubeconfig
  }

  async function removeCluster(id: string): Promise<boolean> {
    const data = await mutation<{ kubernetes: { removeCluster: boolean } }>(
      REMOVE_CLUSTER,
      { id },
    )
    return data.kubernetes.removeCluster
  }

  return { registerCluster, updateCluster, rotateKubeconfig, removeCluster }
}

/**
 * Maps the studio's mock `WorkloadKind` (`'Deployment' | …`) to the
 * GraphQL enum (`DEPLOYMENT | …`). The mock data uses PascalCase
 * because that's how kubectl renders kinds; the backend enum uses
 * SCREAMING_SNAKE because that's what graphql-codegen produces.
 */
export function toBackendKind(kind: string): K8sWorkloadKind | null {
  switch (kind) {
    case 'Deployment': return 'DEPLOYMENT'
    case 'StatefulSet': return 'STATEFUL_SET'
    case 'DaemonSet': return 'DAEMON_SET'
    case 'ReplicaSet': return 'REPLICA_SET'
    case 'Job': return 'JOB'
    case 'CronJob': return 'CRON_JOB'
    default: return null
  }
}
