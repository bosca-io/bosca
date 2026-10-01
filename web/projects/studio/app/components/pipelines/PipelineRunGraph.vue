<script setup lang="ts">
/**
 * Read-only Vue Flow visualization of a durable pipeline RUN: the pipeline's DAG (the same `graph`
 * JSON the executor runs) with each node painted by its run state and the taken path highlighted.
 *
 * It deliberately reuses the editor's building blocks — PipelineNodeCard for nodes, computePipelineLayout
 * for graphs stored without positions, and the same edge/handle conventions — but strips every editing
 * affordance (no dragging, connecting, or selecting). Live updates flow in through the `runNodes` prop:
 * the modal recomputes it from the run subscription and we repaint without rebuilding the layout, so the
 * graph advances in place without losing the viewer's pan/zoom.
 */
import { VueFlow, useVueFlow, type Node as FlowNode, type Edge as FlowEdge } from '@vue-flow/core'
import PipelineNodeCard from '~/components/pipelines/PipelineNodeCard.vue'
import { computePipelineLayout, type LayoutNode, type LayoutEdge } from '~/components/pipelines/pipelineLayout'
import type { RunVizNode, RunVizState } from '~/components/pipelines/pipelineRunViz'
// Layout + handle/edge visuals — same pair the editor loads (see [id].vue): the run graph can be
// rendered from the runs page, which never imports these otherwise.
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

// Mirrors the backend Pipeline graph shape (a subset of the editor's StoredNode/StoredEdge).
interface StoredNode { type: string, id: string, position?: { x: number, y: number }, [key: string]: unknown }
interface StoredEdge { id: string, source: string, target: string, sourcePort?: string | null, targetPort?: string | null }
interface NodeOutputSlotMeta { name: string, kind: string, error: boolean }
interface NodeInputSlotMeta { name: string, kind: string, typeLabel: string, description: string | null, required: boolean }
interface NodeType {
  key: string
  label: string
  category: string
  inputs: NodeInputSlotMeta[]
  outputs: NodeOutputSlotMeta[]
  /** Setting descriptors, when the host fetched them — a PIPELINE reference puts the corner peek on the card. */
  settings?: { name: string, reference: string | null }[]
}
// Flat, non-recursive views of a hydrated node/edge — just the fields applyRunState reads/writes. A real
// GraphNode/GraphEdge back-references the rest of the graph, so spreading one to repaint recurses the
// whole structure (TS2589); reading through these keeps the repaint's object spread shallow.
interface VizNode { id: string, data: Record<string, unknown>, [k: string]: unknown }
interface VizEdge { source: string, target: string, sourceHandle?: string, animated?: boolean, class?: string, [k: string]: unknown }

const props = withDefaults(defineProps<{
  /** The pipeline's stored graph — `graph` from the Pipeline (a parsed JSON scalar). */
  graph: { nodes?: StoredNode[], edges?: StoredEdge[] }
  /** The node-type palette (`pipelines.nodeTypes`) — supplies each node's category, label, and ports. */
  nodeTypes: NodeType[]
  /** The run's resolved per-node visual state; changes as the run subscription advances. */
  runNodes: RunVizNode[]
  /** Off when the canvas shows structure only (an empty `runNodes`) — e.g. PipelinePeekModal — where a run-state legend would explain colors that never appear. */
  showLegend?: boolean
}>(), { showLegend: true })
// The referencing node's id rides along so a run-context host can resolve the node's CHILD RUNS
// (the peek modal only needs the pipeline id; the run detail needs both).
const emit = defineEmits<{ peek: [target: { nodeId: string, pipelineId: string }] }>()

// A unique store id keeps this canvas isolated from the editor's Vue Flow instance.
const flowId = `pipeline-run-graph-${useId()}`
const { fitView, onPaneReady } = useVueFlow(flowId)

// Estimated card footprint, used only for the fallback layout (origin-stacked graphs).
const NODE_W = 180
const NODE_H = 64

const nodes = ref<FlowNode[]>([])
const edges = ref<FlowEdge[]>([])
const hasNodes = computed(() => (props.graph.nodes?.length ?? 0) > 0)

function typeOf(key: string): NodeType | undefined {
  return props.nodeTypes.find(t => t.key === key)
}
// Mirrors PipelineNodeCard.hasMultipleInputs / the editor's nodeHasInputPorts: a node renders per-slot
// input handles only when it declares more than one, so only then does an edge attach by named handle.
function nodeHasInputPorts(type: string | undefined): boolean {
  return (typeOf(String(type))?.inputs?.length ?? 0) > 1
}

/** Build the static graph (nodes, edges, positions). Run state is layered on separately by applyRunState. */
function buildBase() {
  const storedNodes = props.graph.nodes ?? []
  const storedEdges = props.graph.edges ?? []

  // Only lay out when EVERY node sits at the origin — the seeded/git-synced/API-created case that would
  // otherwise stack on top of each other. A graph the editor saved already carries real positions.
  const needsLayout = storedNodes.length > 1
    && storedNodes.every(n => !n.position || (n.position.x === 0 && n.position.y === 0))
  let positions: Map<string, { x: number, y: number }> | null = null
  if (needsLayout) {
    const layoutNodes: LayoutNode[] = storedNodes.map(n => ({ id: n.id, width: NODE_W, height: NODE_H }))
    const layoutEdges: LayoutEdge[] = storedEdges.map(e => ({ source: e.source, target: e.target }))
    positions = computePipelineLayout(layoutNodes, layoutEdges)
  }

  const kindById = new Map(storedNodes.map(n => [n.id, n.type]))

  // Cast the finished arrays rather than annotate the callbacks: Vue Flow's `Node`/`Edge` types are
  // self-referential generics, so inferring the object literals against them trips TS2589 (excessively
  // deep). Letting them infer as plain literals and casting once keeps the recursive generic out of
  // inference — the same `as unknown as Flow*` escape the editor uses (see [id].vue onNodeDragStop).
  nodes.value = storedNodes.map((stored) => {
    const { type, id, position } = stored
    const meta = typeOf(type)
    // Input/Output are structural (no @PipelineNodeType descriptor); derive their category + label by
    // kind so the card renders the right handles (Input: no inbound, Output: no outbound).
    const category = type === 'input' ? 'INPUT' : type === 'output' ? 'OUTPUT' : (meta?.category ?? 'TRANSFORM')
    const label = type === 'input' ? 'Input' : type === 'output' ? 'Output' : (meta?.label ?? type)
    const settings = (stored.settings && typeof stored.settings === 'object')
      ? stored.settings as Record<string, unknown>
      : (() => { const { type: _t, id: _i, position: _p, ...rest } = stored; return rest as Record<string, unknown> })()
    // Same rule as the editor's nodePeekTarget: the type's first PIPELINE-reference setting with a value.
    const peekPipelineId = (meta?.settings ?? [])
      .filter(s => s.reference === 'PIPELINE')
      .map(s => settings[s.name])
      .find((v): v is string => typeof v === 'string' && v.length > 0)
    return {
      id,
      type: 'pipeline',
      position: positions?.get(id) ?? { x: position?.x ?? 0, y: position?.y ?? 0 },
      draggable: false,
      selectable: false,
      connectable: false,
      data: {
        kind: type,
        label,
        category,
        outputs: meta?.outputs ?? [],
        inputs: meta?.inputs ?? [],
        settings,
        peekPipelineId,
        runState: undefined as RunVizState | undefined,
      },
    }
  }) as unknown as FlowNode[]

  edges.value = storedEdges.map((e) => {
    const targetHasPorts = nodeHasInputPorts(kindById.get(e.target))
    return {
      id: e.id,
      source: e.source,
      target: e.target,
      // sourceHandle = the routed branch; targetHandle attaches to a named input slot, else the port
      // rides as the edge label — exactly as the editor hydrates edges (toFlowEdge).
      sourceHandle: e.sourcePort ?? undefined,
      targetHandle: targetHasPorts ? (e.targetPort ?? undefined) : undefined,
      label: targetHasPorts ? undefined : (e.targetPort ?? undefined),
    }
  }) as unknown as FlowEdge[]

  applyRunState()
  refit()
}

/** A node counts as "on the path" once the run reached it in any non-skipped state. */
const REACHED: ReadonlySet<RunVizState> = new Set<RunVizState>(['ran', 'error', 'running', 'awaiting'])

/** Paint run state onto nodes and highlight the edges along the path the run actually took. */
function applyRunState() {
  const stateById = new Map(props.runNodes.map(r => [r.nodeId, r]))
  // Read through flat views (see VizNode/VizEdge) so these repaint spreads stay shallow.
  const flatNodes = nodes.value as unknown as VizNode[]
  nodes.value = flatNodes.map(n => ({ ...n, data: { ...n.data, runState: stateById.get(n.id)?.state } })) as unknown as FlowNode[]
  const flatEdges = edges.value as unknown as VizEdge[]
  edges.value = flatEdges.map((e) => {
    const src = stateById.get(e.source)
    const tgt = stateById.get(e.target)
    const sourceReached = src ? REACHED.has(src.state) : false
    const targetReached = tgt ? REACHED.has(tgt.state) : false
    // When the source routed to a specific port (Condition/Switch), only the matching edge is on the
    // path; a single-output node has no sourceHandle, so every reached downstream edge counts.
    const portMatch = !src?.port || !e.sourceHandle || e.sourceHandle === src.port
    const active = sourceReached && targetReached && portMatch
    const dim = src?.state === 'skipped' || tgt?.state === 'skipped'
    return { ...e, animated: active, class: active ? 'edge-active' : dim ? 'edge-dim' : undefined }
  }) as unknown as FlowEdge[]
}

function refit() {
  // Defer until the nodes have been measured, or fitView centers on zero-size nodes.
  nextTick(() => fitView({ padding: 0.2 }))
}

onPaneReady(() => fitView({ padding: 0.2 }))
onMounted(buildBase)
// Rebuild on a new pipeline/palette (graph identity changes once the modal loads it); repaint only on
// run progress so live updates keep the viewer's pan/zoom.
watch(() => [props.graph, props.nodeTypes], buildBase)
watch(() => props.runNodes, applyRunState, { deep: true })

const LEGEND: { state: RunVizState, label: string }[] = [
  { state: 'ran', label: 'Completed' },
  { state: 'running', label: 'Running' },
  { state: 'awaiting', label: 'Awaiting' },
  { state: 'error', label: 'Failed' },
  { state: 'skipped', label: 'Skipped' },
]
function legendColor(state: RunVizState): string {
  switch (state) {
    case 'ran': return 'var(--ok, #34d399)'
    case 'running': return 'var(--info, #38bdf8)'
    case 'awaiting': return 'var(--warning, #fbbf24)'
    case 'error': return 'var(--danger, #f87171)'
    case 'skipped': return 'var(--fg-3, #94a3b8)'
  }
}
</script>

<template>
  <div class="run-graph">
    <div
      v-if="!hasNodes"
      class="run-graph-empty"
    >
      This pipeline has no nodes to display.
    </div>
    <template v-else>
      <VueFlow
        :id="flowId"
        v-model:nodes="nodes"
        v-model:edges="edges"
        fit-view-on-init
        :min-zoom="0.2"
        :max-zoom="2"
        :nodes-draggable="false"
        :nodes-connectable="false"
        :elements-selectable="false"
        :delete-key-code="null"
      >
        <template #node-pipeline="slotProps">
          <PipelineNodeCard
            :data="slotProps.data"
            :peek-pipeline-id="(slotProps.data.peekPipelineId as string | undefined)"
            @peek="(id: string) => emit('peek', { nodeId: String(slotProps.id), pipelineId: id })"
          />
        </template>
      </VueFlow>

      <div
        v-if="showLegend"
        class="run-graph-legend"
      >
        <span
          v-for="item in LEGEND"
          :key="item.state"
          class="legend-item"
        >
          <span
            class="legend-swatch"
            :style="{ background: legendColor(item.state) }"
          />
          {{ item.label }}
        </span>
      </div>

      <Button
        class="run-graph-fit"
        size="xs"
        icon="maximize"
        @click="fitView({ padding: 0.2 })"
      >
        Fit
      </Button>
    </template>
  </div>
</template>

<style scoped>
.run-graph {
  position: relative;
  height: 100%;
  min-height: 420px;
  background: var(--bg-2, #0f172a);
  border: 1px solid var(--line, rgba(148, 163, 184, 0.18));
  border-radius: var(--r-md, 10px);
  overflow: hidden;
}
.run-graph-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--fg-3, #94a3b8);
  font-size: 13px;
}
/* The taken path: active edges accent + animate; a not-taken branch (skipped endpoint) fades. */
.run-graph :deep(.vue-flow__edge.edge-active .vue-flow__edge-path) {
  stroke: var(--accent, #f59e0b);
  stroke-width: 2.5;
}
.run-graph :deep(.vue-flow__edge.edge-dim) {
  opacity: 0.25;
}
.run-graph-legend {
  position: absolute;
  left: 10px;
  bottom: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 6px 10px;
  border-radius: 8px;
  background: color-mix(in oklch, var(--bg-1, #0b1220) 82%, transparent);
  border: 1px solid var(--line, rgba(148, 163, 184, 0.18));
  font-size: 11px;
  color: var(--fg-2, #cbd5e1);
  pointer-events: none;
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.legend-swatch {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  flex: 0 0 auto;
}
.run-graph-fit {
  position: absolute;
  top: 10px;
  right: 10px;
}
</style>
