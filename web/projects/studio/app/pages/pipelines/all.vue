<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import PipelineRunsModal from '~/components/pipelines/PipelineRunsModal.vue'

/**
 * Pipelines — stored, reusable transform graphs. A pipeline accepts a typed input (usually a
 * platform event), threads it through transform/action nodes, and optionally produces an output.
 * Triggers reference pipelines (Event → Pipeline); analytics processing can drive them too.
 */

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

interface PipelineRow {
  id: string
  name: string
  description: string
  acceptedInputType: string
  tags: string[]
  outputType: string | null
  hasOutput: boolean
  version: number
}

/** A pipeline whose stored graph no longer decodes — can't be opened or run, only deleted. */
interface BrokenPipelineRow {
  id: string
  name: string
  key: string
  error: string
}

const { data, status, refresh } = useAsyncQuery<{
  pipelines: { all: PipelineRow[], broken: BrokenPipelineRow[] }
}>('pipelines-all', gql`
  query GetPipelines {
    pipelines {
      all { id name description acceptedInputType tags outputType hasOutput version }
      broken { id name key error }
    }
  }
`, {})
const pipelines = computed(() => data.value?.pipelines?.all ?? [])
const broken = computed(() => data.value?.pipelines?.broken ?? [])

// Tag facets: every distinct tag across the catalog, and the subset the user is filtering on.
// Selecting tags narrows the list to pipelines carrying *any* selected tag (OR); none = show all.
const allTags = computed(() =>
  [...new Set(pipelines.value.flatMap(p => p.tags ?? []))].sort((a, b) => a.localeCompare(b)),
)
const selectedTags = ref<string[]>([])
function toggleTag(tag: string) {
  selectedTags.value = selectedTags.value.includes(tag)
    ? selectedTags.value.filter(t => t !== tag)
    : [...selectedTags.value, tag]
}
const filteredPipelines = computed(() =>
  selectedTags.value.length === 0
    ? pipelines.value
    : pipelines.value.filter(p => (p.tags ?? []).some(t => selectedTags.value.includes(t))),
)

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(200px, 2fr)' },
  { key: 'acceptedInputType', label: 'Input → Output', width: 'minmax(260px, 2fr)' },
  { key: 'version', label: 'Version', width: '80px' },
]
const rowActions = [
  { id: 'edit', label: 'Edit', icon: 'pencil' },
  { id: 'runs', label: 'Run History', icon: 'history' },
  { id: 'clone', label: 'Clone', icon: 'copy' },
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
]

const brokenColumns: GlassTableColumn[] = [
  { key: 'name', label: 'Name', width: 'minmax(160px, 1fr)' },
  { key: 'key', label: 'Key', width: 'minmax(120px, 1fr)' },
  { key: 'error', label: 'Error', width: 'minmax(240px, 2fr)' },
]
const brokenRowActions = [
  { id: 'delete', label: 'Delete', icon: 'trash', danger: true },
]

/** Pipeline whose run history is open, or null when the modal is closed. */
const runsFor = ref<PipelineRow | null>(null)

/** Show the readable leaf of the accepted input's fully-qualified type. */
function acceptsLabel(fqdn: string): string {
  const leaf = fqdn.includes('.') ? fqdn.slice(fqdn.lastIndexOf('.') + 1) : fqdn
  return leaf.replace(/([a-z0-9])([A-Z])/g, '$1 $2').trim() || fqdn
}

function openEditor(id: string) {
  navigateTo(`/pipelines/${id}`)
}

async function clone(row: PipelineRow) {
  try {
    const res = await gqlMutation(gql`
      mutation ClonePipeline($id: UUID!) {
        pipelines { clone(id: $id) { id } }
      }
    `, { id: row.id })
    const cloned = (res as { pipelines?: { clone?: { id: string } } } | null)?.pipelines?.clone
    toast.success('Pipeline cloned (inactive)')
    if (cloned?.id) navigateTo(`/pipelines/${cloned.id}`)
    else refresh()
  }
  catch {
    toast.error('Failed to clone pipeline')
  }
}

async function deleteById(id: string, successMessage: string) {
  try {
    await gqlMutation(gql`
      mutation DeletePipeline($id: UUID!) {
        pipelines { delete(id: $id) }
      }
    `, { id })
    toast.success(successMessage)
    refresh()
  }
  catch {
    toast.error('Failed to delete pipeline')
  }
}

function onRowAction(payload: { action: string, row: PipelineRow }) {
  if (payload.action === 'edit') openEditor(payload.row.id)
  else if (payload.action === 'runs') runsFor.value = payload.row
  else if (payload.action === 'clone') clone(payload.row)
  else if (payload.action === 'delete') deleteById(payload.row.id, 'Pipeline deleted')
}

function onBrokenRowAction(payload: { action: string, row: BrokenPipelineRow }) {
  if (payload.action === 'delete') deleteById(payload.row.id, 'Broken pipeline deleted')
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Pipelines', 'All Pipelines')"
        title="Pipelines"
        subtitle="Reusable transform graphs — fed by triggers and other subsystems"
      >
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="navigateTo('/pipelines/new')"
          >
            New Pipeline
          </Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard
      v-if="broken.length"
      title="Couldn't be loaded"
      subtitle="These pipelines' stored graphs no longer decode — a node type was renamed or removed, or the data is corrupt. They can't be opened or run; delete them here."
    >
      <GlassTable
        :columns="brokenColumns"
        :rows="broken"
        :row-actions="brokenRowActions"
        @row-action="onBrokenRowAction"
      >
        <template #col-error="{ row }">
          <code class="error-cell">{{ row.error }}</code>
        </template>
      </GlassTable>
    </SectionCard>

    <SectionCard
      title="All Pipelines"
      subtitle="A pipeline accepts a typed input (usually an event) and runs a graph of transform and action nodes."
    >
      <div
        v-if="allTags.length"
        class="tag-filter"
      >
        <button
          v-for="tag in allTags"
          :key="tag"
          class="filter-chip"
          :class="{ active: selectedTags.includes(tag) }"
          @click="toggleTag(tag)"
        >
          <Icon
            name="tag"
            :size="11"
            :color="selectedTags.includes(tag) ? accent : 'var(--fg-3)'"
          />
          {{ tag }}
        </button>
        <button
          v-if="selectedTags.length"
          class="filter-chip clear"
          @click="selectedTags = []"
        >
          Clear
        </button>
      </div>

      <GlassTable
        :columns="columns"
        :rows="filteredPipelines"
        :loading="status === 'pending'"
        :row-actions="rowActions"
        empty-message="No pipelines yet — create one to get started."
        @row-click="(row: PipelineRow) => openEditor(row.id)"
        @row-action="onRowAction"
      >
        <template #col-name="{ row }">
          <div class="name-cell">
            <span>{{ row.name }}</span>
            <span
              v-if="row.description"
              class="desc"
            >{{ row.description }}</span>
            <div
              v-if="row.tags?.length"
              class="row-tags"
            >
              <Badge
                v-for="tag in row.tags"
                :key="tag"
                :color="accent"
              >{{ tag }}</Badge>
            </div>
          </div>
        </template>
        <template #col-acceptedInputType="{ row }">
          <code class="accepts">
            {{ acceptsLabel(row.acceptedInputType) }}
            <span class="arrow">→</span>
            {{ row.hasOutput ? (row.outputType || 'output') : 'side effects' }}
          </code>
        </template>
      </GlassTable>
    </SectionCard>

    <PipelineRunsModal
      v-if="runsFor"
      :pipeline-id="runsFor.id"
      :pipeline-name="runsFor.name"
      @close="runsFor = null"
    />
  </PageShell>
</template>

<style scoped>
.name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.desc {
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
.row-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

/* Tag facet bar — click a tag to narrow the list (OR across selected tags).
   A symmetric band between the card header and the table; 16px horizontal inset lines the chips
   up with the table's rows, 12px top/bottom gives even breathing room above and below. */
.tag-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  flex-wrap: wrap;
}
.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 6px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  color: var(--fg-1);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.filter-chip:hover {
  border-color: v-bind(accent);
}
.filter-chip.active {
  background: color-mix(in oklch, v-bind(accent) 12%, transparent);
  border-color: color-mix(in oklch, v-bind(accent) 40%, transparent);
}
.filter-chip.clear {
  color: var(--fg-3);
}
.accepts {
  font-size: 11px;
}
.arrow {
  color: var(--text-muted, #94a3b8);
}
.error-cell {
  font-size: 11px;
  color: var(--danger, #f87171);
  word-break: break-word;
}
</style>
