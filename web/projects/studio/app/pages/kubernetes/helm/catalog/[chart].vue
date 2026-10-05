<script setup lang="ts">
import { computed } from 'vue'
import type { K8sHelmChart } from '~/composables/useK8sTypes'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()

const id = computed(() => route.params.chart as string)

// The chart id encodes `${repo}/${name}` — split before issuing the
// catalog reads so the resolvers can scope queries to a single chart.
const split = computed(() => {
  const [r, ...rest] = (id.value || '').split('/')
  return { repo: r ?? '', name: rest.join('/') }
})
const repoParam = computed(() => split.value.repo)
const chartParam = computed(() => split.value.name)

const { data: chartsData } = useK8sHelmCharts({ repo: repoParam, search: chartParam })
const chart = computed<K8sHelmChart | undefined>(() =>
  (chartsData.value ?? []).find(c => c.id === id.value || (c.repo === repoParam.value && c.name === chartParam.value)),
)

const { data: versionsData } = useK8sHelmChartVersions({
  repo: () => chart.value?.repo,
  chart: () => chart.value?.name,
})
const versions = computed(() => (versionsData.value ?? []).map(v => ({
  version: v.version,
  appVersion: v.appVersion,
  releasedAt: v.released,
  current: v.current,
})))

const { data: valuesData } = useK8sHelmChartValues({
  repo: () => chart.value?.repo,
  chart: () => chart.value?.name,
  version: () => chart.value?.version,
})
const defaultValues = computed(() => valuesData.value?.defaultValues ?? '')

const readme = computed(() => chart.value
  ? `# ${chart.value.name}

${chart.value.description}

## Prerequisites

- Kubernetes 1.27+
- Helm 3.12+

## Install

\`\`\`bash
helm install my-${chart.value.name} ${chart.value.repo}/${chart.value.name} \\
  --version ${chart.value.version}
\`\`\`

The values schema (if the chart ships one) is rendered alongside the
default values to the right.
`
  : ''
)

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
        :breadcrumb="[{ label: 'Kubernetes', to: '/kubernetes/overview' }, { label: 'Helm Catalog', to: '/kubernetes/helm/catalog' }, chart?.name || id]"
        :title="chart?.name || id"
        :subtitle="chart ? `${chart.repo} · app v${chart.appVersion}` : ''"
      >
        <template #actions>
          <NuxtLink v-if="chart" :to="`/kubernetes/helm/catalog/install?chart=${chart.id}`">
            <Button
              primary
              size="sm"
              :accent="accent"
              icon="download">Install</Button>
          </NuxtLink>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div v-if="!chart" class="empty">Chart not found.</div>

    <template v-else>
      <SectionCard padded glass>
        <div class="hero">
          <div class="icon-tile" :style="{ background: gradientFor(chart.name) }">
            <span class="initial">{{ chart.name.slice(0, 2).toUpperCase() }}</span>
          </div>
          <div class="hero-info">
            <h2 class="hero-title">{{ chart.name }}</h2>
            <p class="hero-desc">{{ chart.description }}</p>
            <div class="hero-meta">
              <Badge color="#5ec5ff">latest v{{ chart.version }}</Badge>
              <Badge color="#a78bff">app v{{ chart.appVersion }}</Badge>
              <span class="mono dim">{{ chart.repo }}</span>
            </div>
          </div>
        </div>
      </SectionCard>

      <div class="cols">
        <SectionCard padded glass>
          <template #header><h3 class="card-h">Versions</h3></template>
          <div class="versions">
            <div v-for="v in versions" :key="v.version" class="version-row">
              <span class="mono v" :style="{ fontWeight: v.current ? 600 : 400 }">{{ v.version }}</span>
              <span class="mono dim">app v{{ v.appVersion }}</span>
              <span class="dim">{{ v.releasedAt }}</span>
              <Badge v-if="v.current" color="#34d99a">latest</Badge>
              <span v-else />
            </div>
          </div>
        </SectionCard>

        <SectionCard padded glass>
          <template #header><h3 class="card-h">Default values</h3></template>
          <CodeEditor
            :model-value="defaultValues"
            language="text"
            :rows="18"
            readonly />
        </SectionCard>
      </div>

      <SectionCard padded glass>
        <template #header><h3 class="card-h">README</h3></template>
        <pre class="readme">{{ readme }}</pre>
      </SectionCard>
    </template>
  </PageShell>
</template>

<style scoped>
.empty { padding: 32px; text-align: center; color: var(--fg-3); }
.card-h { margin: 0; font-size: 14px; font-weight: 600; }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); }

.hero { display: flex; gap: 16px; align-items: center; }
.icon-tile { width: 56px; height: 56px; border-radius: 12px; display: grid; place-items: center; flex-shrink: 0; box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25); }
.initial { color: #fff; font-weight: 600; font-size: 16px; letter-spacing: 0.04em; }
.hero-info { flex: 1; min-width: 0; }
.hero-title { margin: 0; font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
.hero-desc { margin: 4px 0 8px; font-size: 13px; color: var(--fg-2); }
.hero-meta { display: flex; align-items: center; gap: 8px; }

.cols { display: grid; grid-template-columns: 360px 1fr; gap: 14px; }
@media (max-width: 1000px) { .cols { grid-template-columns: 1fr; } }

.versions { display: flex; flex-direction: column; gap: 6px; }
.version-row {
  display: grid;
  grid-template-columns: 90px 1fr 80px 60px;
  align-items: center;
  gap: 10px;
  padding: 6px 4px;
  border-bottom: 1px solid var(--line);
  font-size: 12px;
}
.version-row:last-child { border-bottom: none; }
.v { color: var(--fg-1); }

.readme {
  margin: 0;
  padding: 16px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--fg-1);
  white-space: pre-wrap;
  overflow-x: auto;
}
</style>
