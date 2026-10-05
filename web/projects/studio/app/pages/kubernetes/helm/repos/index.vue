<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
const toast = useToast()

const clusterIdRef = computed(() => current.value?.id)
const mutations = useK8sMutations({ cluster: clusterIdRef })

const { data: reposData, status: queryStatus, refresh } = useK8sHelmRepos()
const HELM_REPOS = computed(() => reposData.value ?? [])
const isLoading = computed(() => queryStatus.value === 'pending')

const search = ref('')
const showAdd = ref(false)
const form = ref({ name: '', url: '', username: '', password: '' })
const saving = ref(false)
const refreshing = ref(false)
const removing = ref<string | null>(null)

const filtered = computed(() => HELM_REPOS.value.filter(r => !search.value || `${r.name} ${r.url}`.toLowerCase().includes(search.value.toLowerCase())))

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 1fr)' },
  { key: 'url', label: 'URL', width: 'minmax(280px, 2.5fr)' },
  { key: 'type', label: 'Type', width: '80px' },
  { key: 'charts', label: 'Charts', width: '80px', align: 'right' },
  { key: 'lastUpdate', label: 'Last update', width: '120px', muted: true },
]

function rowActions(): OverflowMenuItem[] {
  return [
    { id: 'browse', label: 'Browse charts', icon: 'boxes' },
    { id: 'refresh', label: 'Refresh index', icon: 'refresh' },
    { id: 'separator', label: '', separator: true },
    { id: 'remove', label: 'Remove', icon: 'trash', danger: true },
  ]
}

async function onAction({ action, row }: { action: string; row: K8sHelmRepo }) {
  if (action === 'browse') {
    navigateTo(`/kubernetes/helm/catalog?repo=${row.name}`)
  } else if (action === 'refresh') {
    try {
      refreshing.value = true
      await mutations.helmRepoUpdate()
      toast.success(`Refreshed ${row.name}`)
      await refresh()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : 'Refresh failed')
    } finally {
      refreshing.value = false
    }
  } else if (action === 'remove') {
    try {
      removing.value = row.name
      await mutations.helmRepoRemove(row.name)
      toast.success(`Removed ${row.name}`)
      await refresh()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : 'Remove failed')
    } finally {
      removing.value = null
    }
  }
}

async function refreshAll() {
  try {
    refreshing.value = true
    await mutations.helmRepoUpdate()
    await refresh()
    toast.success('Refreshed all repos')
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Refresh failed')
  } finally {
    refreshing.value = false
  }
}

async function submitAdd() {
  if (!form.value.name.trim() || !form.value.url.trim()) return
  saving.value = true
  try {
    await mutations.helmRepoAdd({
      name: form.value.name.trim(),
      url: form.value.url.trim(),
      username: form.value.username.trim() || null,
      password: form.value.password || null,
    })
    toast.success(`Added repo ${form.value.name}`)
    showAdd.value = false
    form.value = { name: '', url: '', username: '', password: '' }
    await refresh()
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : 'Add failed')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Helm Repos')"
        title="Helm Repos"
        :subtitle="`${HELM_REPOS.length} repositories · ${HELM_REPOS.reduce((s, r) => s + r.charts, 0)} charts total`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="refreshing || isLoading"
            @click="refreshAll">Refresh all</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showAdd = true">Add repo</Button>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Filter repos…" class="search" />
      <span class="spacer" />
    </div>

    <SectionCard glass>
      <GlassTable
        :columns="columns"
        :rows="filtered"
        row-key="name"
        :row-actions="rowActions"
        :loading="isLoading"
        loading-text="Loading repos…"
        empty-text="No Helm repos configured."
        @row-action="onAction">
        <template #col-name="{ row }"><span class="mono name">{{ row.name }}</span></template>
        <template #col-url="{ row }"><span class="mono dim">{{ row.url }}</span></template>
        <template #col-type="{ row }">
          <Badge :color="row.type === 'oci' ? '#a78bff' : '#5ec5ff'">{{ row.type }}</Badge>
        </template>
        <template #col-charts="{ row }"><span class="mono">{{ row.charts }}</span></template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showAdd"
      title="Add Helm repo"
      icon="plus"
      :accent="accent"
      @close="showAdd = false">
      <div class="form-stack">
        <TextInput v-model="form.name" label="Name" placeholder="e.g. prometheus-community" />
        <TextInput v-model="form.url" label="URL" placeholder="https://… or oci://…" />
        <TextInput v-model="form.username" label="Username (optional)" placeholder="For private repos" />
        <TextInput
          v-model="form.password"
          label="Password (optional)"
          type="password"
          placeholder="••••••••" />
        <p class="hint">Credentials are stored encrypted and used only by kubernetes-controller.</p>
      </div>
      <template #footer>
        <Button @click="showAdd = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="submitAdd">Add</Button>
      </template>
    </Modal>
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.name { font-weight: 500; font-size: 12px; }
.mono { font-family: var(--font-mono); font-size: 12px; font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }
.form-stack { display: flex; flex-direction: column; gap: 12px; }
.hint { font-size: 11.5px; color: var(--fg-3); margin: 0; }
</style>
