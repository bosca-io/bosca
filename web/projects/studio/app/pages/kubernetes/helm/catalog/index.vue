<script setup lang="ts">
import { computed, ref } from 'vue'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()

const route = useRoute()

const search = ref('')
const repoFilter = ref<string>((route.query.repo as string | undefined) ?? 'all')

const { data: reposData, refresh: refreshRepos } = useK8sHelmRepos()
const repoFilterEffective = computed(() => repoFilter.value === 'all' ? null : repoFilter.value)
const { data: chartsData, status: chartsStatus, refresh: refreshCharts } = useK8sHelmCharts({
  repo: repoFilterEffective,
  search,
})
const HELM_REPOS = computed(() => reposData.value ?? [])
const HELM_CHARTS = computed(() => chartsData.value ?? [])
const isLoading = computed(() => chartsStatus.value === 'pending')

const repoOptions = computed(() => [
  { label: 'All repos', value: 'all' },
  ...HELM_REPOS.value.map(r => ({ label: r.name, value: r.name })),
])

const filtered = computed(() => HELM_CHARTS.value)

function refreshAll() {
  refreshRepos()
  refreshCharts()
}

function gradientFor(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash)
  const hue = Math.abs(hash) % 360
  return `linear-gradient(135deg, oklch(0.7 0.12 ${hue}), oklch(0.55 0.18 ${(hue + 50) % 360}))`
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Helm Catalog')"
        title="Helm Catalog"
        :subtitle="`${HELM_CHARTS.length} charts across ${HELM_REPOS.length} repos`"
      >
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :disabled="isLoading"
            @click="refreshAll">Refresh</Button>
          <NuxtLink to="/kubernetes/helm/repos">
            <Button icon="database" size="sm">Manage repos</Button>
          </NuxtLink>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="filters">
      <SearchInput v-model="search" placeholder="Search charts…" class="search" />
      <Select v-model="repoFilter" :options="repoOptions" />
      <span class="spacer" />
      <span class="muted">{{ filtered.length }} of {{ HELM_CHARTS.length }}</span>
    </div>

    <div v-if="isLoading && !filtered.length" class="empty">Loading charts…</div>
    <div v-else-if="!filtered.length" class="empty">No charts match the current filters.</div>
    <div v-else class="grid">
      <SectionCard
        v-for="c in filtered"
        :key="c.id"
        glass
        class="chart-card">
        <div class="card-body">
          <div class="icon-tile" :style="{ background: gradientFor(c.name) }">
            <span class="initial">{{ c.name.slice(0, 2).toUpperCase() }}</span>
          </div>
          <div class="info">
            <div class="title-row">
              <span class="mono title">{{ c.name }}</span>
              <Badge color="#5ec5ff">v{{ c.version }}</Badge>
            </div>
            <div class="mono dim repo">{{ c.repo }}</div>
            <p class="desc">{{ c.description }}</p>
          </div>
        </div>
        <div class="card-foot">
          <span class="app-v mono dim">app v{{ c.appVersion }}</span>
          <NuxtLink :to="`/kubernetes/helm/catalog/install?chart=${c.id}`">
            <Button
              primary
              size="sm"
              :accent="accent"
              icon="download">Install</Button>
          </NuxtLink>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search { flex: 1; min-width: 240px; max-width: 360px; }
.spacer { flex: 1; }
.muted { color: var(--fg-3); font-size: 12px; }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}
.chart-card { padding: 16px; display: flex; flex-direction: column; }
.card-body { display: flex; gap: 14px; align-items: flex-start; flex: 1; }
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
.icon-tile {
  width: 44px; height: 44px;
  border-radius: 10px;
  display: grid; place-items: center;
  flex-shrink: 0;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25);
}
.initial { color: #fff; font-weight: 600; font-size: 14px; letter-spacing: 0.04em; }
.info { flex: 1; min-width: 0; }
.title-row { display: flex; align-items: center; gap: 8px; }
.title { font-weight: 600; font-size: 13.5px; }
.repo { font-size: 11px; margin-top: 2px; }
.desc { margin: 6px 0 0; font-size: 12px; color: var(--fg-2); line-height: 1.45; }
.app-v { font-size: 11px; }
.mono { font-family: var(--font-mono); }
.dim { color: var(--fg-3); }
.empty { padding: 40px; text-align: center; color: var(--fg-3); }
</style>
