<script setup lang="ts">
import gql from 'graphql-tag'
import PipelineRunGraph from '~/components/pipelines/PipelineRunGraph.vue'
import type { RunVizNode } from '~/components/pipelines/pipelineRunViz'

/**
 * A read-only "peek" into a pipeline another pipeline references (a For Each body, a Run Pipeline
 * target), opened from the node inspector without leaving — or risking — the editing session:
 * "Open in editor" deliberately opens a new tab so unsaved work in the current editor survives.
 *
 * Peeks nest. Which settings point at pipelines is read from the node-type descriptors
 * (`settings[].reference === 'PIPELINE'`), so when the shown pipeline itself references pipelines
 * they surface as drill-down chips and a breadcrumb trail walks back up — any node kind that gains
 * a pipeline reference participates with no change here.
 */

const props = defineProps<{
  pipelineId: string
  /** Shown as the title until the pipeline loads (the picker already knows the name). */
  pipelineName?: string
}>()
const emit = defineEmits<{ close: [] }>()

const { query: gqlQuery } = useGraphQL()

// Mirrors the backend Pipeline graph shape (the same subset PipelineRunGraph reads).
interface StoredNode { type: string, id: string, position?: { x: number, y: number }, settings?: unknown, [k: string]: unknown }
interface StoredEdge { id: string, source: string, target: string, sourcePort?: string | null, targetPort?: string | null }
interface GraphStruct { nodes?: StoredNode[], edges?: StoredEdge[] }
interface SettingRefMeta { name: string, reference: string | null, fields: { name: string, reference: string | null }[] }
interface NodeInputSlotMeta { name: string, kind: string, typeLabel: string, description: string | null, required: boolean }
interface NodeOutputSlotMeta { name: string, kind: string, error: boolean }
interface PeekNodeType { key: string, label: string, category: string, inputs: NodeInputSlotMeta[], outputs: NodeOutputSlotMeta[], settings: SettingRefMeta[] }
interface PeekPipeline { id: string, name: string, description: string | null, graph: GraphStruct }

// The palette (for node rendering + which settings reference pipelines) and the id → name catalog
// for chip/crumb labels — static for the modal's lifetime, fetched once.
const CONTEXT_QUERY = gql`
  query GetPipelinePeekContext {
    pipelines {
      nodeTypes {
        key label category
        inputs { name kind typeLabel description required }
        outputs { name kind error }
        settings { name reference fields { name reference } }
      }
      all { id name }
    }
  }
`

const PIPELINE_QUERY = gql`
  query GetPipelinePeek($id: UUID!) {
    pipelines {
      pipeline(id: $id) { id name description graph }
    }
  }
`

const contextState = ref<'loading' | 'ready' | 'error'>('loading')
const nodeTypes = ref<PeekNodeType[]>([])
const namesById = ref(new Map<string, string>())

// Every pipeline peeked so far, so walking back up the trail is instant. `null` records a load
// failure (deleted pipeline, network) — distinct from "not fetched yet" (absent key).
const loaded = reactive<Record<string, PeekPipeline | null>>({})

// The peek trail: pipeline ids from the original reference down to the one on screen.
const trail = ref<string[]>([props.pipelineId])
const currentId = computed(() => trail.value[trail.value.length - 1]!)
const current = computed(() => loaded[currentId.value])

const breadcrumbs = computed(() => trail.value.map(id => ({ id, name: nameOf(id) })))

function nameOf(id: string): string {
  return loaded[id]?.name ?? namesById.value.get(id) ?? (id === props.pipelineId ? props.pipelineName ?? id : id)
}

async function loadContext() {
  try {
    const res = await gqlQuery<{ pipelines: { nodeTypes: PeekNodeType[], all: { id: string, name: string }[] } }>(CONTEXT_QUERY)
    nodeTypes.value = res?.pipelines?.nodeTypes ?? []
    namesById.value = new Map((res?.pipelines?.all ?? []).map(p => [p.id, p.name]))
    contextState.value = 'ready'
  }
  catch {
    contextState.value = 'error'
  }
}

async function ensureLoaded(id: string) {
  if (id in loaded) return
  try {
    const res = await gqlQuery<{ pipelines: { pipeline: (Omit<PeekPipeline, 'graph'> & { graph: unknown }) | null } }>(PIPELINE_QUERY, { id })
    const pipeline = res?.pipelines?.pipeline ?? null
    // graph is a JSON scalar — it arrives already parsed.
    const g = (pipeline?.graph ?? {}) as GraphStruct
    loaded[id] = pipeline ? { ...pipeline, graph: { nodes: g.nodes ?? [], edges: g.edges ?? [] } } : null
  }
  catch {
    loaded[id] = null
  }
}

onMounted(loadContext)
watch(currentId, id => { void ensureLoaded(id) }, { immediate: true })

function peekDeeper(id: string) {
  trail.value = [...trail.value, id]
}
function popTo(index: number) {
  trail.value = trail.value.slice(0, index + 1)
}

/** One pipeline the shown pipeline references, and the node it's referenced from (for the tooltip). */
interface ChildRef { id: string, name: string, via: string }

// Scans the shown graph for settings the node's descriptor declares as PIPELINE references —
// both direct slots and GROUP_LIST row fields — deduped by target pipeline.
const childRefs = computed<ChildRef[]>(() => {
  const byId = new Map<string, ChildRef>()
  for (const node of current.value?.graph.nodes ?? []) {
    const meta = nodeTypes.value.find(t => t.key === node.type)
    if (!meta) continue
    // Settings live under `settings`, or flattened onto the node in older stored graphs —
    // the same fallback PipelineRunGraph applies when it paints node names.
    const settings = (node.settings && typeof node.settings === 'object')
      ? node.settings as Record<string, unknown>
      : node as Record<string, unknown>
    const via = (typeof settings.name === 'string' && settings.name.trim().length > 0) ? settings.name : meta.label
    const add = (value: unknown) => {
      if (typeof value !== 'string' || !value || byId.has(value)) return
      byId.set(value, { id: value, name: nameOf(value), via })
    }
    for (const slot of meta.settings) {
      if (slot.reference === 'PIPELINE') add(settings[slot.name])
      const refFields = slot.fields.filter(f => f.reference === 'PIPELINE')
      if (refFields.length && Array.isArray(settings[slot.name])) {
        for (const row of settings[slot.name] as unknown[]) {
          if (row && typeof row === 'object') refFields.forEach(f => add((row as Record<string, unknown>)[f.name]))
        }
      }
    }
  }
  return [...byId.values()]
})

// Structure only — no run to overlay.
const NO_RUN_STATE: RunVizNode[] = []
</script>

<template>
  <Modal
    :title="current?.name ?? pipelineName ?? 'Pipeline'"
    :subtitle="current?.description || undefined"
    icon="workflow"
    width="min(95vw, 1200px)"
    @close="emit('close')"
  >
    <div class="peek">
      <div class="peek-bar">
        <nav
          v-if="breadcrumbs.length > 1"
          class="crumbs"
          aria-label="Peek trail"
        >
          <template
            v-for="(crumb, i) in breadcrumbs"
            :key="`${crumb.id}-${i}`"
          >
            <Icon
              v-if="i > 0"
              name="chevronRight"
              :size="12"
              color="var(--fg-3)"
            />
            <button
              v-if="i < breadcrumbs.length - 1"
              type="button"
              class="crumb"
              @click="popTo(i)"
            >
              {{ crumb.name }}
            </button>
            <span
              v-else
              class="crumb current"
            >{{ crumb.name }}</span>
          </template>
        </nav>
        <NuxtLink
          class="editor-link"
          :to="`/pipelines/${currentId}`"
          target="_blank"
        >
          Open in editor
          <Icon
            name="external-link"
            :size="12"
          />
        </NuxtLink>
      </div>

      <div class="graph-host">
        <div
          v-if="current === null || contextState === 'error'"
          class="graph-state"
        >
          Could not load this pipeline — it may have been deleted.
        </div>
        <div
          v-else-if="!current || contextState === 'loading'"
          class="graph-state"
        >
          Loading pipeline…
        </div>
        <ClientOnly v-else>
          <PipelineRunGraph
            :graph="current.graph"
            :node-types="nodeTypes"
            :run-nodes="NO_RUN_STATE"
            :show-legend="false"
            @peek="(t: { nodeId: string, pipelineId: string }) => peekDeeper(t.pipelineId)"
          />
          <template #fallback>
            <div class="graph-state">
              Loading graph…
            </div>
          </template>
        </ClientOnly>
      </div>

      <div
        v-if="childRefs.length"
        class="children"
      >
        <span class="children-label">Peek deeper:</span>
        <button
          v-for="child in childRefs"
          :key="child.id"
          type="button"
          class="child-chip"
          :title="`Referenced by ${child.via}`"
          @click="peekDeeper(child.id)"
        >
          <Icon
            name="eye"
            :size="12"
          />
          {{ child.name }}
        </button>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.peek {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.peek-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 20px;
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  min-width: 0;
}
.crumb {
  border: none;
  background: transparent;
  padding: 2px 4px;
  border-radius: 6px;
  font: inherit;
  font-size: 12px;
  color: var(--text-muted, #94a3b8);
  cursor: pointer;
  white-space: nowrap;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.crumb:hover {
  color: var(--text, #e2e8f0);
  background: var(--surface-2, rgba(148, 163, 184, 0.08));
}
.crumb.current {
  color: var(--text, #e2e8f0);
  cursor: default;
}
.crumb.current:hover {
  background: transparent;
}
.editor-link {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--accent, #f59e0b);
  text-decoration: none;
  white-space: nowrap;
}
.editor-link:hover {
  text-decoration: underline;
}
.graph-host {
  height: min(70vh, 720px);
}
.graph-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-muted, #94a3b8);
  font-size: 13px;
}
.children {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.children-label {
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
.child-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border: 1px solid var(--line, rgba(148, 163, 184, 0.18));
  border-radius: 999px;
  background: var(--surface-2, rgba(148, 163, 184, 0.06));
  color: var(--text, #e2e8f0);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.child-chip:hover {
  border-color: color-mix(in oklch, var(--accent, #f59e0b) 45%, transparent);
  background: color-mix(in oklch, var(--accent, #f59e0b) 10%, transparent);
}
</style>
