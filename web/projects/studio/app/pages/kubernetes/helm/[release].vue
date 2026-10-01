<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn } from '@bosca/ui'
import type { K8sHelmRelease } from '~/composables/useK8sTypes'
import type { HelmUninstallTarget } from '~/components/kubernetes/HelmUninstallModal.vue'
import type { HelmUpgradeTarget } from '~/components/kubernetes/HelmUpgradeModal.vue'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const toast = useToast()

// Stay `undefined` when no cluster is selected (skip semantics in
// useAsyncQuery); a `null` fallback would send `cluster: null` to a
// required `UUID!` arg and trigger a server-side validation error.
const clusterIdRef = computed(() => current.value?.id)
const mutations = useK8sMutations({ cluster: clusterIdRef })

const id = computed(() => decodeURIComponent(route.params.release as string))
const { data: releasesData, refresh: refreshReleases } = useK8sHelmReleases({ cluster: clusterIdRef })
const release = computed<K8sHelmRelease | undefined>(() =>
  (releasesData.value ?? []).find(r => r.name === id.value),
)

const { data: historyData, refresh: refreshHistory } = useK8sHelmReleaseHistory({
  cluster: clusterIdRef,
  namespace: () => release.value?.ns,
  name: () => release.value?.name,
})

const tabs = ['Overview', 'Values', 'Manifest', 'History', 'Notes']
const tab = ref('Overview')

const { data: valuesData, status: valuesStatus, refresh: refreshValues } = useK8sHelmReleaseValues({
  cluster: clusterIdRef,
  namespace: () => release.value?.ns,
  name: () => release.value?.name,
})
const { data: manifestData, status: manifestStatus, refresh: refreshManifest } = useK8sHelmReleaseManifest({
  cluster: clusterIdRef,
  namespace: () => release.value?.ns,
  name: () => release.value?.name,
})

// Live refresh — a new revision (upgrade / rollback) or status flip
// writes an `owner=helm` Secret; re-run every tab's query so the
// revision, history, values, and manifest stay realtime.
useK8sResourceWatch({
  cluster: clusterIdRef,
  namespace: () => release.value?.ns,
  kinds: ['HelmRelease'],
  onChange: () => { refreshReleases(); refreshHistory(); refreshValues(); refreshManifest() },
})

const valuesYaml = computed(() => valuesData.value ?? '')
const manifestYaml = computed(() => manifestData.value ?? '')

const history = computed(() => historyData.value ?? [])

const rollingBack = ref(false)
const upgradeTarget = ref<HelmUpgradeTarget | null>(null)
const uninstallTarget = ref<HelmUninstallTarget | null>(null)

function openUpgrade() {
  const r = release.value
  if (!r) return
  upgradeTarget.value = {
    name: r.name,
    namespace: r.ns,
    repo: r.repo,
    chart: r.chart,
    chartVersion: r.chartVersion,
  }
}
function onUpgraded() {
  upgradeTarget.value = null
  void refreshReleases()
  void refreshHistory()
}

function openUninstall() {
  const r = release.value
  if (!r) return
  uninstallTarget.value = { name: r.name, namespace: r.ns }
}
function onUninstalled() {
  uninstallTarget.value = null
  navigateTo('/kubernetes/helm')
}

async function rollback(toRevision: number) {
  if (!release.value) return
  rollingBack.value = true
  try {
    await mutations.helmRollback({
      namespace: release.value.ns,
      name: release.value.name,
      toRevision,
    })
    toast.success(`Rolled ${release.value.name} back to revision ${toRevision}`)
    await refreshReleases()
    await refreshHistory()
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Rollback failed')
  } finally {
    rollingBack.value = false
  }
}

const historyCols: GlassTableColumn[] = [
  { key: 'revision', label: 'Revision', width: '90px', align: 'right' },
  { key: 'updated', label: 'Updated', width: '110px', muted: true },
  { key: 'status', label: 'Status', width: '120px' },
  { key: 'chart', label: 'Chart', width: 'minmax(240px, 2fr)' },
  { key: 'appVersion', label: 'App version', width: '110px' },
  { key: 'description', label: 'Description', width: 'minmax(200px, 1.5fr)', muted: true },
]

function statusLabel(s: string): string {
  switch (s) {
    case 'deployed': return 'Deployed'
    case 'pending': return 'Pending'
    case 'failed': return 'Failed'
    case 'superseded': return 'Superseded'
    default: return s || 'Unknown'
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'Helm Releases', to: '/kubernetes/helm' }, release?.name || id]"
        :title="release?.name || id"
        :tabs="tabs"
        :active-tab="tab"
        @tab="(v) => tab = v"
      >
        <template v-if="release" #subtitle>
          <K8sStatusBadge :status="statusLabel(release.status)" :pulse="release.status !== 'deployed'" />
          <span class="sub-text">{{ release.ns }} · {{ release.chart }}-{{ release.chartVersion }}</span>
        </template>
        <template #actions>
          <Button
            size="sm"
            icon="refresh"
            :disabled="!history.length || rollingBack"
            @click="rollback(history.length >= 2 && history[1] ? history[1].revision : (release?.revision || 1) - 1)">
            {{ rollingBack ? 'Rolling back…' : 'Rollback' }}
          </Button>
          <Button
            primary
            size="sm"
            :accent="accent"
            icon="arrowUpRight"
            @click="openUpgrade">Upgrade…</Button>
          <Button
            size="sm"
            icon="trash"
            @click="openUninstall">Uninstall</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="!release" class="empty">Release not found.</div>

    <template v-else>
      <div v-if="tab === 'Overview'" class="grid">
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Release</h3></template>
          <div class="kv">
            <div class="k">Namespace</div><div class="v mono">{{ release.ns }}</div>
            <div class="k">Chart</div><div class="v mono">{{ release.chart }}-{{ release.chartVersion }}</div>
            <div class="k">App version</div><div class="v mono">v{{ release.appVersion }}</div>
            <div class="k">Revision</div><div class="v mono">{{ release.revision }}</div>
            <div class="k">Last updated</div><div class="v">{{ release.updated }}</div>
            <div class="k">Installed</div><div class="v">{{ release.installed }}</div>
          </div>
        </SectionCard>
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Source</h3></template>
          <div class="kv">
            <div class="k">Repo</div><div class="v mono">{{ release.repo }}</div>
            <div class="k">URL</div><div class="v mono break">{{ release.repoUrl }}</div>
            <div class="k">Description</div><div class="v">{{ release.description }}</div>
          </div>
        </SectionCard>
      </div>

      <SectionCard v-else-if="tab === 'Values'" padded glass>
        <template #header>
          <div class="card-row">
            <h3 class="card-h">Effective values (chart defaults + overrides)</h3>
            <span v-if="valuesStatus === 'pending'" class="sub-text">Loading…</span>
          </div>
        </template>
        <CodeEditor
          :model-value="valuesYaml"
          language="text"
          :rows="24"
          readonly />
      </SectionCard>

      <SectionCard v-else-if="tab === 'Manifest'" padded glass>
        <template #header>
          <div class="card-row">
            <h3 class="card-h">Rendered manifest</h3>
            <span v-if="manifestStatus === 'pending'" class="sub-text">Loading…</span>
          </div>
        </template>
        <CodeEditor
          :model-value="manifestYaml"
          language="text"
          :rows="24"
          readonly />
      </SectionCard>

      <SectionCard v-else-if="tab === 'History'" glass>
        <GlassTable
          :columns="historyCols"
          :rows="history"
          row-key="revision"
          :arrow="false">
          <template #col-revision="{ row }"><span class="mono">{{ row.revision }}</span></template>
          <template #col-chart="{ row }"><span class="mono">{{ row.chart }}</span></template>
          <template #col-appVersion="{ row }"><span class="mono">v{{ row.appVersion }}</span></template>
          <template #col-status="{ row }">
            <K8sStatusBadge :status="statusLabel(row.status)" :pulse="row.status === 'pending'" />
          </template>
        </GlassTable>
      </SectionCard>

      <SectionCard v-else-if="tab === 'Notes'" padded glass>
        <pre class="notes">{{ `Release "${release.name}" has been deployed.

USEFUL COMMANDS:
  helm status ${release.name} -n ${release.ns}
  helm get values ${release.name} -n ${release.ns}
  helm history ${release.name} -n ${release.ns}

Service is reachable inside the cluster at:
  ${release.name}.${release.ns}.svc.cluster.local
` }}</pre>
      </SectionCard>
    </template>

    <HelmUpgradeModal
      :release="upgradeTarget"
      :accent="accent"
      @close="upgradeTarget = null"
      @upgraded="onUpgraded" />

    <HelmUninstallModal
      :release="uninstallTarget"
      @close="uninstallTarget = null"
      @uninstalled="onUninstalled" />
  </PageShell>
</template>

<style scoped>
.sub-text { color: var(--fg-2); }
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
}
.card-h { margin: 0; font-size: 14px; font-weight: 600; }
.card-row { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.kv { display: grid; grid-template-columns: 130px 1fr; row-gap: 8px; column-gap: 14px; font-size: 12.5px; }
.k { color: var(--fg-3); font-size: 11.5px; text-transform: uppercase; letter-spacing: 0.06em; padding-top: 2px; }
.v { color: var(--fg-1); }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.break { word-break: break-all; }
.empty { padding: 32px; text-align: center; color: var(--fg-3); }

.notes {
  margin: 0;
  padding: 16px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  white-space: pre;
}
</style>
