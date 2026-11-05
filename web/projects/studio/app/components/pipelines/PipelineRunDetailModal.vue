<script setup lang="ts">
import gql from 'graphql-tag'
import { pipelineEventLabel } from '~/components/pipelines/pipelineEventLabel'
import PipelinePeekModal from '~/components/pipelines/PipelinePeekModal.vue'
import PipelineRunGraph from '~/components/pipelines/PipelineRunGraph.vue'
import { deriveRunVizNodes, resolveNodeName, resolveNodeTransitions, type NodeTransition, type RunVizNode } from '~/components/pipelines/pipelineRunViz'

/**
 * Run-detail view: the live state of one durable run plus its per-node
 * execution timeline (status, timing, routed port, error, output snapshot). Opened from Active Runs
 * (in-flight) and from run history (finished, via the row's runId).
 *
 * Two ways to read the same run: a Graph view overlays the run state on the pipeline's DAG (the path
 * taken, where it's running/parked/failed), and a Timeline view lists the per-node executions in order
 * with their outputs. Both are fed by the same live `pipelineRun` subscription.
 */

const props = defineProps<{ runId: string }>()
const emit = defineEmits<{ close: [] }>()

const { query: gqlQuery, mutation: gqlMutation, useSubscription } = useGraphQL()
const toast = useToast()

interface NodeExecution {
  nodeId: string
  status: string
  startedAt: string
  finishedAt: string
  durationMs: number
  port: string | null
  error: string | null
  output: unknown
}
interface RunDetail {
  id: string
  pipelineId: string
  status: string
  eventName: string
  input: Record<string, unknown> | null
  error: string | null
  createdAt: string
  modifiedAt: string
  awaitingNodeIds: string[]
  awaitingNodes: Array<{ nodeId: string; type: string; name: string; runId: string | null }>
  completedNodeIds: string[]
  nodes: NodeExecution[]
  steps: RunStep[]
}
/** One step-oriented progress row: an authored Status milestone or a human wait. */
interface RunStep {
  nodeId: string
  title: string
  kind: 'STATUS' | 'HUMAN'
  status: 'PENDING' | 'RUNNING' | 'WAITING' | 'DONE' | 'FAILED'
  depth: number
  item: string | null
}
/** A live run/node status change pushed over the `pipelineRun` subscription. */
interface PipelineRunUpdate {
  runId: string
  runStatus: string | null
  nodeId: string | null
  nodeStatus: string | null
  port: string | null
  error: string | null
  at: string
}

const run = ref<RunDetail | null>(null)
const loading = ref(false)
const expanded = ref<Set<number>>(new Set())

const RUN_DETAIL_QUERY = gql`
  query GetPipelineRunDetail($runId: UUID!) {
    pipelines {
      run(runId: $runId) {
        id pipelineId status eventName input error createdAt modifiedAt
        awaitingNodeIds completedNodeIds
        awaitingNodes { nodeId type name runId }
        steps { nodeId title kind status depth item channelType }
        nodes { nodeId status startedAt finishedAt durationMs port error output }
      }
    }
  }
`

async function refresh(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await gqlQuery<{ pipelines: { run: RunDetail | null } }>(RUN_DETAIL_QUERY, { runId: props.runId })
    run.value = res?.pipelines?.run ?? null
    if (!run.value && !silent) toast.error('Run not found')
    // A run with authored Status milestones opens on the steps view — observers see steps, not engine nodes.
    if (!viewChosen && (run.value?.steps?.length ?? 0) > 0) view.value = 'steps'
  }
  catch {
    if (!silent) toast.error('Failed to load the run')
  }
  finally {
    if (!silent) loading.value = false
  }
}

onMounted(() => refresh())

// The subscription only carries THIS run's own transitions — progress inside child runs (where the
// relay's gates and builds live) publishes under the child run ids, so poll while the run is live.
let livePoll: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  livePoll = setInterval(() => {
    if (typeof document !== 'undefined' && document.hidden) return
    const s = run.value?.status
    if (s === 'RUNNING' || s === 'SUSPENDED') refresh(true)
  }, 5000)
})
onUnmounted(() => { if (livePoll) clearInterval(livePoll) })

// Live updates: stream run + per-node status changes so the timeline
// advances with no manual refresh. A run-level update changes the status badge instantly; every update
// also schedules a debounced, spinner-less refetch that brings in the full node rows (durations,
// outputs) the lightweight update doesn't carry.
const RUN_UPDATES_SUBSCRIPTION = gql`
  subscription PipelineRunUpdates($runId: UUID!) {
    pipelineRun(runId: $runId) { runId runStatus nodeId nodeStatus port error at }
  }
`
let refreshTimer: ReturnType<typeof setTimeout> | undefined
function refreshSoon() {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => refresh(true), 250)
}
useSubscription<{ pipelineRun?: PipelineRunUpdate }>(RUN_UPDATES_SUBSCRIPTION, { runId: props.runId }, (data) => {
  const update = data?.pipelineRun
  if (!update) return
  if (update.nodeId == null && update.runStatus && run.value) {
    run.value = { ...run.value, status: update.runStatus, error: update.error ?? run.value.error }
  }
  refreshSoon()
})
onUnmounted(() => { if (refreshTimer) clearTimeout(refreshTimer) })

// ─── Graph view: the pipeline's DAG with run state overlaid ──────────────────────────────────
type ViewMode = 'steps' | 'graph' | 'timeline'
const view = ref<ViewMode>('graph')
// Once the user picks a tab, the steps auto-default stops overriding it.
let viewChosen = false
function chooseView(mode: ViewMode) {
  viewChosen = true
  view.value = mode
}

const STEP_COLORS: Record<RunStep['status'], string> = {
  PENDING: 'var(--fg-3)',
  RUNNING: 'var(--info, #38bdf8)',
  WAITING: '#ffb547',
  DONE: 'var(--ok, #34d99a)',
  FAILED: 'var(--err, #f87171)',
}

// Rows sit flush — nesting is conveyed by the per-item chip, not indentation.
const displaySteps = computed(() => run.value?.steps ?? [])

interface NodeOutputSlotMeta { name: string, kind: string, error: boolean }
interface NodeInputSlotMeta { name: string, kind: string, typeLabel: string, description: string | null, required: boolean }
interface NodeType { key: string, label: string, category: string, inputs: NodeInputSlotMeta[], outputs: NodeOutputSlotMeta[], settings: { name: string, reference: string | null }[] }
interface GraphStruct {
  nodes?: { type: string, id: string, position?: { x: number, y: number }, [k: string]: unknown }[]
  edges?: { id: string, source: string, target: string, sourcePort?: string | null, targetPort?: string | null }[]
}

const nodeTypes = ref<NodeType[]>([])
const graphStruct = ref<GraphStruct | null>(null)
const graphLoading = ref(false)
const graphError = ref(false)
// The pipeline a graph is loaded for, so we fetch the structure (and the static palette) only once.
const graphLoadedFor = ref<string | null>(null)

const GRAPH_QUERY = gql`
  query GetPipelineRunGraph($id: UUID!) {
    pipelines {
      nodeTypes { key label category inputs { name kind typeLabel description required } outputs { name kind error } settings { name reference } }
      pipeline(id: $id) { id graph }
    }
  }
`

async function loadGraph(pipelineId: string) {
  if (graphLoadedFor.value === pipelineId) return
  graphLoading.value = true
  graphError.value = false
  try {
    const res = await gqlQuery<{ pipelines: { nodeTypes: NodeType[], pipeline: { graph: unknown } | null } }>(GRAPH_QUERY, { id: pipelineId })
    nodeTypes.value = res?.pipelines?.nodeTypes ?? []
    // graph is a JSON scalar — it arrives already parsed.
    const g = (res?.pipelines?.pipeline?.graph ?? {}) as GraphStruct
    graphStruct.value = { nodes: g.nodes ?? [], edges: g.edges ?? [] }
    graphLoadedFor.value = pipelineId
  }
  catch {
    graphError.value = true
    graphStruct.value = null
  }
  finally {
    graphLoading.value = false
  }
}

// Load the pipeline's structure as soon as the run tells us which pipeline it belongs to.
watch(() => run.value?.pipelineId, (id) => { if (id) loadGraph(id) })

// ─── Human-in-the-loop: a SUSPENDED run parked on a gate / input node is decided from here ───────

/** One awaited human decision: an Approval Gate (approve/reject) or a Wait for Input (a value). */
interface AwaitingAction {
  nodeId: string
  /** The run actually parked on the node — a nested gate's CHILD run, not this modal's run. */
  runId: string
  kind: 'gate' | 'input'
  /** The node's prompt (its authored question), falling back to its name. */
  prompt: string
}

const awaitingActions = computed<AwaitingAction[]>(() => {
  if (run.value?.status !== 'SUSPENDED') return []
  // Drilled THROUGH fan-outs: a gate inside a child pipeline surfaces here with its own run id.
  return (run.value.awaitingNodes ?? []).flatMap((n): AwaitingAction[] => {
    const runId = n.runId ?? props.runId
    if (n.type === 'gate.approval') return [{ nodeId: n.nodeId, runId, kind: 'gate' as const, prompt: n.name }]
    if (n.type === 'waitForInput') return [{ nodeId: n.nodeId, runId, kind: 'input' as const, prompt: n.name }]
    return []
  })
})

const gateNotes = reactive<Record<string, string>>({})
const inputValues = reactive<Record<string, string>>({})
const deciding = ref(false)

// ── Cancel — stops a stuck or mistaken run from right here ────────────────────
const cancelling = ref(false)
async function cancelRun() {
  cancelling.value = true
  try {
    await gqlMutation(gql`
      mutation CancelRunFromModal($runId: UUID!) {
        pipelines { cancelRun(runId: $runId) }
      }
    `, { runId: props.runId })
    toast.success('Run cancelled')
    await refresh(true)
  } catch {
    toast.error('Failed to cancel the run')
  } finally {
    cancelling.value = false
  }
}
const isLive = computed(() => run.value?.status === 'RUNNING' || run.value?.status === 'SUSPENDED')
const githubRepositoryId = computed(() => {
  if (run.value?.eventName !== 'bosca.git.model.GitHubDelivery') return null
  const id = run.value.input?.repositoryId
  return typeof id === 'string' ? id : null
})

/** Decide an Approval Gate: approve lets the gated value flow on; reject fails the run. */
async function decideGate(runId: string, nodeId: string, approved: boolean) {
  deciding.value = true
  try {
    await gqlMutation(gql`
      mutation ResolveGate($runId: UUID!, $nodeId: String!, $approved: Boolean!, $note: String) {
        pipelines { resolveGate(runId: $runId, nodeId: $nodeId, approved: $approved, note: $note) }
      }
    `, { runId, nodeId, approved, note: gateNotes[nodeId]?.trim() || null })
    toast.success(approved ? 'Approved — the run is resuming' : 'Rejected — the run has been failed')
    await refresh(true)
  }
  catch {
    toast.error('Failed to record the decision')
  }
  finally {
    deciding.value = false
  }
}

/** Supply the value a Wait for Input node is parked on (JSON, or a bare string). */
async function provideInput(runId: string, nodeId: string) {
  const raw = inputValues[nodeId]?.trim() ?? ''
  let value: unknown
  try {
    value = raw.length > 0 ? JSON.parse(raw) : {}
  }
  catch {
    // Not JSON — send it as a plain string value.
    value = raw
  }
  deciding.value = true
  try {
    await gqlMutation(gql`
      mutation ProvideInput($runId: UUID!, $nodeId: String!, $input: JSON!) {
        pipelines { provideInput(runId: $runId, nodeId: $nodeId, input: $input) }
      }
    `, { runId, nodeId, input: value })
    toast.success('Input provided — the run is resuming')
    await refresh(true)
  }
  catch {
    toast.error('Failed to provide the input')
  }
  finally {
    deciding.value = false
  }
}

/** The run's per-node visual state, recomputed as the subscription advances run.value. */
const runVizNodes = computed<RunVizNode[]>(() => {
  const r = run.value
  if (!r) return []
  return deriveRunVizNodes({
    nodes: r.nodes.map(n => ({ nodeId: n.nodeId, status: n.status, port: n.port })),
    completedNodeIds: r.completedNodeIds ?? [],
    awaitingNodeIds: r.awaitingNodeIds ?? [],
  })
})

/** One timeline entry, enriched with the operator-facing node name and the transitions it took. */
interface TimelineRow extends NodeExecution {
  index: number
  name: string
  /** Show the raw nodeId as a subtitle only when it differs from the resolved name (i.e. there IS a name). */
  showId: boolean
  transitions: NodeTransition[]
}

// Resolve each execution's name + transitions against the loaded pipeline graph. Until the graph
// arrives (or if the pipeline was edited away from this run), names fall back to the raw nodeId and
// transitions to the bare routed port — recomputes when the graph loads or the subscription advances.
const timeline = computed<TimelineRow[]>(() => {
  const nodes = run.value?.nodes ?? []
  const graph = graphStruct.value
  const types = nodeTypes.value
  return nodes.map((n, index) => {
    const name = graph ? resolveNodeName(n.nodeId, graph, types) : n.nodeId
    return {
      ...n,
      index,
      name,
      showId: name !== n.nodeId,
      transitions: graph ? resolveNodeTransitions(n.nodeId, n.port, graph, types) : [],
    }
  })
})

// Graph mode needs room to read the DAG; the timeline reads fine in the narrower column.
const modalWidth = computed(() => view.value === 'graph' ? 'min(95vw, 1200px)' : 'min(92vw, 880px)')

// ─── Drill into a child run (the corner eye on a For Each / Run Pipeline node) ──────────────────
// In a run context the eye opens what the child actually DID — the node's child runs — not the
// child pipeline's structure. One child (Run Pipeline) opens its run detail directly; a For Each
// fan-out offers a per-item picker; a node that never fanned out falls back to the structure peek.
interface ChildRunRow {
  id: string
  status: string
  itemIndex: number | null
  error: string | null
  createdAt: string
  modifiedAt: string
}
const CHILD_RUNS_QUERY = gql`
  query GetPipelineRunChildren($runId: UUID!, $nodeId: String!) {
    pipelines {
      run(runId: $runId) {
        childRuns(nodeId: $nodeId) { id status itemIndex error createdAt modifiedAt }
      }
    }
  }
`
const childRunId = ref<string | null>(null)
const childPicker = ref<ChildRunRow[] | null>(null)
const peekPipelineId = ref<string | null>(null)

async function openChild(target: { nodeId: string, pipelineId: string }) {
  try {
    const res = await gqlQuery<{ pipelines: { run: { childRuns: ChildRunRow[] } | null } }>(
      CHILD_RUNS_QUERY,
      { runId: props.runId, nodeId: target.nodeId },
    )
    const children = res?.pipelines?.run?.childRuns ?? []
    if (children.length === 1) childRunId.value = children[0]?.id ?? null
    else if (children.length > 1) childPicker.value = children
    else peekPipelineId.value = target.pipelineId
  }
  catch {
    toast.error('Failed to load the child runs')
  }
}

function childLabel(row: ChildRunRow): string {
  return row.itemIndex != null ? `Item ${row.itemIndex + 1}` : 'Child run'
}

function toggle(i: number) {
  const next = new Set(expanded.value)
  if (next.has(i)) next.delete(i)
  else next.add(i)
  expanded.value = next
}

function hasDetail(n: NodeExecution): boolean {
  return n.error != null || (n.output != null && n.output !== '')
}


function formatTime(d: string): string {
  return new Date(d).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  const sec = Math.round(ms / 1000)
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60)
  return `${m}m ${sec % 60}s`
}

function outputText(o: unknown): string {
  return o == null ? '' : JSON.stringify(o, null, 2)
}

function statusColor(status: string): string {
  switch (status) {
    case 'OK': return 'var(--ok, #34d399)'
    case 'FAILED': return 'var(--err, #f87171)'
    case 'SUSPENDED': return 'var(--accent, #f59e0b)'
    case 'RUNNING': return 'var(--info, #38bdf8)'
    case 'CANCELLED': return 'var(--muted, #94a3b8)'
    case 'SKIPPED': return 'var(--muted, #94a3b8)'
    default: return 'var(--muted, #94a3b8)'
  }
}
</script>

<template>
  <Modal
    title="Run detail"
    :subtitle="run ? pipelineEventLabel(run.eventName) : 'Loading…'"
    icon="workflow"
    :width="modalWidth"
    @close="emit('close')"
  >
    <div
      v-if="loading"
      class="detail-loading"
    >
      Loading run…
    </div>

    <div
      v-else-if="run"
      class="detail"
    >
      <div class="run-header">
        <span
          class="status-badge"
          :style="{
            color: statusColor(run.status),
            background: `color-mix(in oklch, ${statusColor(run.status)} 16%, transparent)`,
          }"
        >{{ run.status }}</span>
        <span class="meta">Started {{ formatTime(run.createdAt) }}</span>
        <span class="meta">Updated {{ formatTime(run.modifiedAt) }}</span>
        <NuxtLink
          class="pipeline-link"
          :to="`/pipelines/${run.pipelineId}`"
        >
          Open pipeline
        </NuxtLink>
        <Button
          v-if="isLive"
          size="sm"
          icon="x"
          :disabled="cancelling"
          title="Cancel this run — parked work is abandoned and the run is marked cancelled"
          @click="cancelRun">Cancel run</Button>
      </div>
      <p
        v-if="run.error"
        class="run-error"
        role="alert"
      >
        {{ run.error }}
      </p>
      <div v-if="run.error && run.status === 'SUSPENDED'" class="failure-help">
        The latest attempt failed. This run is waiting while its backing work retries.
      </div>
      <div v-if="run.error && githubRepositoryId" class="failure-help">
        <p>For GitHub imports, map the sender to a Bosca user and give one of that user's groups repository Edit. A delivery received without a mapping keeps that identity; use Pull from GitHub for existing refs after configuring the mapping.</p>
        <NuxtLink to="/git/settings/github">GitHub user mappings</NuxtLink>
        <NuxtLink :to="`/git/repositories/${githubRepositoryId}?tab=Settings&setting=permissions`">Repository permissions</NuxtLink>
        <NuxtLink :to="`/git/repositories/${githubRepositoryId}?tab=Settings&setting=github`">GitHub synchronization history</NuxtLink>
      </div>

      <div
        v-for="action in awaitingActions"
        :key="action.nodeId"
        class="awaiting-card"
      >
        <div class="awaiting-head">
          <Icon
            :name="action.kind === 'gate' ? 'shield-question' : 'message-circle-question'"
            :size="14"
          />
          <span class="awaiting-title">{{ action.kind === 'gate' ? 'Awaiting approval' : 'Awaiting input' }}</span>
        </div>
        <p class="awaiting-prompt">
          {{ action.prompt }}
        </p>
        <template v-if="action.kind === 'gate'">
          <div class="awaiting-controls">
            <TextInput
              :model-value="gateNotes[action.nodeId] ?? ''"
              placeholder="Optional note (recorded on a rejection)"
              @update:model-value="(v: string) => { gateNotes[action.nodeId] = v }"
            />
            <Button
              primary
              size="sm"
              icon="check"
              :disabled="deciding"
              @click="decideGate(action.runId, action.nodeId, true)"
            >
              Approve
            </Button>
            <Button
              danger
              size="sm"
              icon="x"
              :disabled="deciding"
              @click="decideGate(action.runId, action.nodeId, false)"
            >
              Reject
            </Button>
          </div>
        </template>
        <template v-else>
          <div class="awaiting-controls">
            <TextInput
              :model-value="inputValues[action.nodeId] ?? ''"
              placeholder='The value the run is waiting for — JSON (e.g. {"approved": true}) or plain text'
              @update:model-value="(v: string) => { inputValues[action.nodeId] = v }"
            />
            <Button
              primary
              size="sm"
              icon="send"
              :disabled="deciding"
              @click="provideInput(action.runId, action.nodeId)"
            >
              Provide input
            </Button>
          </div>
        </template>
      </div>

      <div
        class="view-toggle"
        role="tablist"
      >
        <button
          v-if="run.steps.length"
          type="button"
          role="tab"
          class="view-tab"
          :class="{ active: view === 'steps' }"
          :aria-selected="view === 'steps'"
          @click="chooseView('steps')"
        >
          <Icon
            name="check"
            :size="13"
          />
          Steps
        </button>
        <button
          type="button"
          role="tab"
          class="view-tab"
          :class="{ active: view === 'graph' }"
          :aria-selected="view === 'graph'"
          @click="chooseView('graph')"
        >
          <Icon
            name="workflow"
            :size="13"
          />
          Graph
        </button>
        <button
          type="button"
          role="tab"
          class="view-tab"
          :class="{ active: view === 'timeline' }"
          :aria-selected="view === 'timeline'"
          @click="chooseView('timeline')"
        >
          <Icon
            name="list"
            :size="13"
          />
          Timeline
        </button>
      </div>

      <div
        v-if="view === 'steps'"
        class="steps-list"
      >
        <div
          v-for="(step, i) in displaySteps"
          :key="`${step.nodeId}-${i}`"
          class="step-row"
        >
          <span
            class="step-dot"
            :style="{ background: STEP_COLORS[step.status] }"
          />
          <Icon
            v-if="step.kind === 'HUMAN'"
            name="shield-question"
            :size="13"
            color="var(--fg-3)"
          />
          <span class="step-title">{{ step.title }}</span>
          <span
            v-if="step.item"
            class="step-item"
          >{{ step.item }}</span>
          <span
            class="step-status"
            :style="{ color: STEP_COLORS[step.status] }"
          >{{ step.status === 'WAITING' ? 'WAITING FOR YOU' : step.status }}</span>
        </div>
      </div>

      <div
        v-if="view === 'graph'"
        class="graph-host"
      >
        <div
          v-if="graphLoading && !graphStruct"
          class="graph-state"
        >
          Loading pipeline…
        </div>
        <div
          v-else-if="graphError"
          class="graph-state"
        >
          Could not load the pipeline graph.
        </div>
        <ClientOnly v-else-if="graphStruct">
          <PipelineRunGraph
            :graph="graphStruct"
            :node-types="nodeTypes"
            :run-nodes="runVizNodes"
            @peek="openChild"
          />
          <template #fallback>
            <div class="graph-state">
              Loading graph…
            </div>
          </template>
        </ClientOnly>
      </div>

      <template v-else-if="view === 'timeline'">
        <h4 class="timeline-title">
          Node timeline
        </h4>
        <p
          v-if="!run.nodes.length"
          class="empty"
        >
          No node executions recorded for this run.
        </p>
        <ol
          v-else
          class="timeline"
        >
          <li
            v-for="n in timeline"
            :key="`${n.nodeId}-${n.index}`"
            class="node"
          >
            <button
              class="node-row"
              :class="{ clickable: hasDetail(n) }"
              type="button"
              @click="hasDetail(n) && toggle(n.index)"
            >
              <span
                class="status-dot"
                :style="{ background: statusColor(n.status) }"
              />
              <span class="node-identity">
                <span class="node-name">{{ n.name }}</span>
                <span
                  v-if="n.showId"
                  class="node-id"
                  :title="n.nodeId"
                >{{ n.nodeId }}</span>
              </span>
              <span class="node-status">{{ n.status }}</span>
              <span
                v-if="n.transitions.length"
                class="node-transitions"
              >
                <span
                  v-for="t in n.transitions"
                  :key="`${t.targetId}-${t.port ?? ''}`"
                  class="node-transition"
                >→ {{ t.targetName }}<span
                  v-if="t.port"
                  class="transition-port"
                > · {{ t.port }}</span></span>
              </span>
              <span
                v-else-if="n.port"
                class="node-port"
              >→ {{ n.port }}</span>
              <span class="node-duration">{{ formatDuration(n.durationMs) }}</span>
              <Icon
                v-if="hasDetail(n)"
                :name="expanded.has(n.index) ? 'chevronDown' : 'chevronRight'"
                :size="13"
              />
            </button>
            <div
              v-if="expanded.has(n.index)"
              class="node-detail"
            >
              <p
                v-if="n.error"
                class="node-error"
              >
                {{ n.error }}
              </p>
              <pre
                v-if="n.output != null && n.output !== ''"
                class="node-output"
              >{{ outputText(n.output) }}</pre>
            </div>
          </li>
        </ol>
      </template>
    </div>

    <div
      v-else
      class="empty"
    >
      Run not found.
    </div>
  </Modal>

  <PipelinePeekModal
    v-if="peekPipelineId"
    :pipeline-id="peekPipelineId"
    @close="peekPipelineId = null"
  />

  <!-- Stays open beneath the child's detail, so closing the child returns to the item list. -->
  <Modal
    v-if="childPicker"
    title="Child runs"
    subtitle="One run per item — open one to inspect it"
    icon="workflow"
    width="min(92vw, 560px)"
    @close="childPicker = null"
  >
    <ol class="child-list">
      <li
        v-for="child in childPicker"
        :key="child.id"
      >
        <button
          type="button"
          class="child-row"
          @click="childRunId = child.id"
        >
          <span
            class="status-dot"
            :style="{ background: statusColor(child.status) }"
          />
          <span class="child-label">{{ childLabel(child) }}</span>
          <span
            v-if="child.error"
            class="child-error"
            :title="child.error"
          >{{ child.error }}</span>
          <span
            class="child-status"
            :style="{ color: statusColor(child.status) }"
          >{{ child.status }}</span>
          <span class="child-time">{{ formatTime(child.modifiedAt) }}</span>
        </button>
      </li>
    </ol>
  </Modal>

  <PipelineRunDetailModal
    v-if="childRunId"
    :run-id="childRunId"
    @close="childRunId = null"
  />
</template>

<style scoped>
.detail-loading,
.empty {
  padding: 16px;
  color: var(--text-muted, #94a3b8);
  font-size: 13px;
}
.detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.run-header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.status-badge {
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.meta {
  font-size: 12px;
  color: var(--text-muted, #94a3b8);
}
.pipeline-link {
  margin-left: auto;
  font-size: 12px;
  color: var(--accent, #f59e0b);
  text-decoration: none;
}
.pipeline-link:hover {
  text-decoration: underline;
}
.run-error {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: color-mix(in oklch, var(--err, #f87171) 12%, transparent);
  color: var(--err, #f87171);
  font-size: 12px;
}
.failure-help { display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 13px; color: var(--fg-2); }
.failure-help p { flex-basis: 100%; margin: 0; line-height: 1.6; }
.failure-help a { text-decoration: underline; }

/* A parked run waiting on a person — the amber card carries the decision controls. */
.awaiting-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid color-mix(in oklch, var(--warn, #fbbf24) 35%, transparent);
  background: color-mix(in oklch, var(--warn, #fbbf24) 8%, transparent);
}
.awaiting-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--warn, #fbbf24);
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.awaiting-prompt {
  margin: 0;
  font-size: 13px;
  color: var(--fg-1, #e2e8f0);
}
.awaiting-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.awaiting-controls > :first-child {
  flex: 1;
}
.view-toggle {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: 8px;
  background: var(--surface-2, rgba(148, 163, 184, 0.08));
  align-self: flex-start;
}
.view-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted, #94a3b8);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.view-tab:hover {
  color: var(--text, #e2e8f0);
}
.view-tab.active {
  background: var(--surface-raised, rgba(15, 23, 42, 0.9));
  color: var(--text, #e2e8f0);
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
.timeline-title {
  margin: 4px 0 0;
  font-size: 13px;
}
.timeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.node-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: 8px;
  background: var(--surface-2, rgba(148, 163, 184, 0.06));
  color: var(--text, #e2e8f0);
  text-align: left;
  font: inherit;
  cursor: default;
}
.node-row.clickable {
  cursor: pointer;
}
.node-row.clickable:hover {
  background: var(--surface-3, rgba(148, 163, 184, 0.12));
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 auto;
}
.node-identity {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.node-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--text, #e2e8f0);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.node-id {
  font-family: var(--font-mono, monospace);
  font-size: 10px;
  color: var(--text-muted, #94a3b8);
}
.node-status {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--text-muted, #94a3b8);
}
.node-transitions {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 8px;
  min-width: 0;
}
.node-transition {
  font-size: 11px;
  color: var(--accent, #f59e0b);
  white-space: nowrap;
}
.transition-port {
  color: var(--text-muted, #94a3b8);
}
.node-port {
  font-size: 11px;
  color: var(--accent, #f59e0b);
}
.node-duration {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
.node-detail {
  padding: 6px 8px 2px 26px;
}
.node-error {
  margin: 0 0 6px;
  color: var(--err, #f87171);
  font-size: 11px;
}
.node-output {
  margin: 0;
  padding: 8px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.6);
  font-size: 11px;
  max-height: 220px;
  overflow: auto;
}

/* ─── Steps view ────────────────────────────────────────────────────────────── */
.steps-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 420px;
  overflow-y: auto;
}

.step-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg-1);
}

.step-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex: 0 0 9px;
}


.step-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-item {
  font-size: 10px;
  color: var(--fg-3);
  background: var(--bg-2);
  padding: 1px 6px;
  border-radius: 4px;
}

.step-status {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

/* ─── Child-run picker (a For Each node's fan-out) ─────────────────────────── */
.child-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 420px;
  overflow-y: auto;
}
.child-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: var(--surface-2, rgba(148, 163, 184, 0.06));
  color: var(--text, #e2e8f0);
  text-align: left;
  font: inherit;
  cursor: pointer;
}
.child-row:hover {
  background: var(--surface-3, rgba(148, 163, 184, 0.12));
}
.child-label {
  font-size: 12px;
  font-weight: 500;
}
.child-error {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--err, #f87171);
}
.child-status {
  margin-left: auto;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.child-time {
  font-size: 11px;
  color: var(--text-muted, #94a3b8);
}
</style>
