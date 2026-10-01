<script setup lang="ts">
import type { VisualizationType } from '@bosca/ui-analytics'

interface AnalyticsArtifactLink {
  kind: 'Saved query' | 'Visualization' | 'Dashboard'
  label: string
  to: string
}

interface AnalyticsInvestigationStep {
  sequence: number
  kind: 'DISCOVERY' | 'QUERY' | 'SAVED_QUERY' | 'ARTIFACT'
  tool: string
  sql?: string
  resultSummary: string
  startedAt: string
  purpose?: string
  conclusion?: string
}

const props = defineProps<{
  name: string
  visualizationType: string
  configuration: Record<string, unknown>
  data: Record<string, unknown>[]
  sourceQuery?: string
  artifacts?: AnalyticsArtifactLink[]
  investigation?: AnalyticsInvestigationStep[]
  busy?: boolean
}>()

const emit = defineEmits<{
  (event: 'save-query' | 'save-visualization'): void
}>()

const supportedTypes = new Set<VisualizationType>([
  'BAR', 'LINE', 'SCATTER', 'PIE', 'DOUGHNUT', 'NUMBER', 'TABLE', 'LABEL',
  'DATEPICKER', 'TOPO_JSON_MAP', 'GEO_POINT_MAP', 'LIVE_SESSIONS_MAP',
])
const inlineType = computed<VisualizationType>(() => {
  const candidate = props.visualizationType.toUpperCase() as VisualizationType
  return supportedTypes.has(candidate) ? candidate : 'TABLE'
})

const expanded = ref(false)
const hasProvenance = computed(() => Boolean(props.sourceQuery || props.investigation?.length))
const hasSavedQuery = computed(() => props.artifacts?.some(artifact => artifact.kind === 'Saved query') ?? false)
const hasSavedVisualization = computed(() => props.artifacts?.some(artifact => artifact.kind === 'Visualization') ?? false)
const hasBackingQuery = computed(() => Boolean(props.sourceQuery?.trim()) || hasSavedQuery.value)
const canSaveQuery = computed(() => Boolean(props.sourceQuery?.trim()) && !hasSavedQuery.value)
const canSaveVisualization = computed(() => hasBackingQuery.value && !hasSavedVisualization.value)

async function copySql(sql: string) {
  await globalThis.navigator?.clipboard?.writeText(sql)
}
</script>

<template>
  <div class="analytics-result">
    <div v-if="artifacts?.length" class="artifact-links" aria-label="Analytics artifacts">
      <NuxtLink
        v-for="artifact in artifacts"
        :key="artifact.to"
        :to="artifact.to"
        class="artifact-chip">
        <Icon name="link" :size="12" />
        <span>{{ artifact.kind }}: {{ artifact.label }}</span>
      </NuxtLink>
    </div>

    <ClientOnly>
      <AnalyticsVisualization
        :name="name"
        :type="inlineType"
        :configuration="configuration"
        :data="data"
        :height="280"
        frameless
      />
    </ClientOnly>

    <div
      v-if="canSaveQuery || canSaveVisualization"
      class="result-actions"
      aria-label="Save analytics result">
      <Button
        v-if="canSaveQuery"
        size="xs"
        icon="database"
        :disabled="busy"
        aria-label="Save query"
        @click="emit('save-query')">Save query</Button>
      <Button
        v-if="canSaveVisualization"
        size="xs"
        icon="save"
        :disabled="busy"
        aria-label="Save visualization"
        @click="emit('save-visualization')">Save visualization</Button>
    </div>

    <div v-if="hasProvenance" class="provenance">
      <button type="button" class="provenance-toggle" @click="expanded = !expanded">
        <Icon name="code" :size="12" />
        <span>{{ expanded ? 'Hide investigation' : investigation?.length ? 'View investigation' : 'View SQL' }}</span>
      </button>

      <div v-if="expanded" class="provenance-body">
        <section v-if="sourceQuery" class="headline-query">
          <div class="sql-header">
            <span>Headline query</span>
            <button
              type="button"
              class="copy-sql"
              aria-label="Copy headline SQL"
              @click="copySql(sourceQuery)">Copy</button>
          </div>
          <pre class="sql"><code>{{ sourceQuery }}</code></pre>
        </section>

        <ol v-if="investigation?.length" class="investigation-timeline">
          <li v-for="step in investigation" :key="step.sequence" class="investigation-step">
            <div class="step-heading">
              <span class="step-number">{{ step.sequence }}</span>
              <span class="step-kind">{{ step.kind }}</span>
              <span class="step-tool">{{ step.tool }}</span>
            </div>
            <p v-if="step.purpose" class="step-annotation">{{ step.purpose }}</p>
            <div v-if="step.sql" class="step-sql">
              <div class="sql-header">
                <span>SQL</span>
                <button
                  type="button"
                  class="copy-sql"
                  :aria-label="`Copy SQL for step ${step.sequence}`"
                  @click="copySql(step.sql)">Copy</button>
              </div>
              <pre class="sql"><code>{{ step.sql }}</code></pre>
            </div>
            <p class="step-result">{{ step.resultSummary }}</p>
            <p v-if="step.conclusion" class="step-annotation conclusion">{{ step.conclusion }}</p>
          </li>
        </ol>
      </div>
    </div>
  </div>
</template>

<style scoped>
.analytics-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.artifact-links {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.artifact-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--fg-1);
  background: color-mix(in oklch, var(--fg-2) 6%, transparent);
  font-size: 11.5px;
  text-decoration: none;
}

.artifact-chip:hover {
  border-color: var(--fg-3);
}

.result-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.provenance {
  margin-top: 4px;
}

.provenance-toggle,
.copy-sql {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--fg-3);
}

.provenance-toggle {
  font-size: 11.5px;
  font-weight: 500;
}

.provenance-toggle:hover,
.copy-sql:hover {
  color: var(--fg-1);
}

.provenance-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 8px;
}

.headline-query,
.step-sql {
  min-width: 0;
}

.sql-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
  color: var(--fg-3);
  font-size: 10.5px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.copy-sql {
  font-size: 10.5px;
  text-transform: none;
}

.sql {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: color-mix(in oklch, var(--fg-2) 6%, transparent);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.5;
  color: var(--fg-1);
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: auto;
}

.investigation-timeline {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.investigation-step {
  padding-left: 14px;
  border-left: 2px solid var(--line);
}

.step-heading {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.step-number,
.step-kind {
  border-radius: 999px;
  font-size: 10px;
  font-weight: 650;
}

.step-number {
  min-width: 19px;
  padding: 2px 5px;
  text-align: center;
  background: var(--fg-2);
  color: var(--bg-0);
}

.step-kind {
  padding: 2px 6px;
  color: var(--fg-2);
  border: 1px solid var(--line);
}

.step-tool {
  color: var(--fg-3);
  font-family: var(--font-mono);
  font-size: 10.5px;
}

.step-annotation,
.step-result {
  margin: 5px 0;
  font-size: 12px;
  line-height: 1.45;
}

.step-annotation {
  color: var(--fg-1);
}

.step-result {
  color: var(--fg-3);
}

.step-annotation.conclusion {
  color: var(--fg-2);
}
</style>
