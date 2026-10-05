<script setup lang="ts">
import gql from 'graphql-tag'
import { resolveNodeName } from '~/components/pipelines/pipelineRunViz'

/**
 * Inline, live relay-run timeline. Renders one durable run's stages in execution
 * order — status, node type, duration — advancing live over the `pipelineRun` subscription. A stage that
 * is a parked `waitForInput` node (its id in `awaitingNodeIds`) surfaces the human-in-the-loop approval
 * inline: the prompt plus an Approve control that calls `provideInput(runId, nodeId, input)` to resume
 * the run. Unlike the run-detail modal (deep graph + outputs), this is the compact panel embedded on the
 * release dashboard so an operator sees what's happening — and acts on it — without leaving the page.
 */

const props = defineProps<{ runId: string }>()
const emit = defineEmits<{ resumed: [] }>()

const { query: gqlQuery, mutation, useSubscription } = useGraphQL()
const toast = useToast()

interface NodeExecution {
  nodeId: string; status: string
  startedAt: string; finishedAt: string; durationMs: number
  port: string | null; error: string | null
}
interface RunDetail {
  id: string; pipelineId: string; status: string; eventName: string; error: string | null
  createdAt: string; modifiedAt: string
  awaitingNodeIds: string[]; completedNodeIds: string[]; nodes: NodeExecution[]
  steps: RunStep[]
}
/** One step-oriented progress row: an authored Status milestone or a human wait. */
interface RunStep {
  nodeId: string; title: string
  kind: 'STATUS' | 'HUMAN'
  status: 'PENDING' | 'RUNNING' | 'WAITING' | 'DONE' | 'FAILED'
  depth: number; item: string | null
  runId: string | null
  type: string | null
  channelType: string | null
}
interface GraphNode { type: string; id: string; prompt?: string; [k: string]: unknown }
interface GraphStruct {
  nodes?: GraphNode[]
  edges?: { id: string; source: string; target: string; sourcePort?: string | null; targetPort?: string | null }[]
}
interface NodeType { key: string; label: string; category: string }

const run = ref<RunDetail | null>(null)
const loading = ref(true)

const RUN_QUERY = gql`
  query RelayRunTimeline($runId: UUID!) {
    pipelines {
      run(runId: $runId) {
        id pipelineId status eventName error createdAt modifiedAt
        awaitingNodeIds completedNodeIds
        steps { nodeId title kind status depth item runId type channelType }
        nodes { nodeId status startedAt finishedAt durationMs port error }
      }
    }
  }
`

async function refresh(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await gqlQuery<{ pipelines: { run: RunDetail | null } }>(RUN_QUERY, { runId: props.runId })
    run.value = res?.pipelines?.run ?? null
  }
  catch {
    if (!silent) toast.error('Failed to load the run')
  }
  finally {
    loading.value = false
  }
}

onMounted(() => refresh())

// Live updates: the subscription pushes run/node transitions; debounce a spinner-less refetch that
// brings in the full node rows (durations, statuses) the lightweight update doesn't carry.
const RUN_UPDATES = gql`
  subscription RelayRunUpdates($runId: UUID!) {
    pipelineRun(runId: $runId) { runId runStatus nodeId nodeStatus port error at }
  }
`
let refreshTimer: ReturnType<typeof setTimeout> | undefined
function refreshSoon() {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => refresh(true), 250)
}
useSubscription<{ pipelineRun?: { runStatus: string | null; nodeId: string | null; error: string | null } }>(
  RUN_UPDATES, { runId: props.runId }, (data) => {
    const update = data?.pipelineRun
    if (!update) return
    if (update.nodeId == null && update.runStatus && run.value) {
      run.value = { ...run.value, status: update.runStatus, error: update.error ?? run.value.error }
    }
    refreshSoon()
  })
let livePoll: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  livePoll = setInterval(() => {
    if (typeof document !== 'undefined' && document.hidden) return
    const s = run.value?.status
    if (s === 'RUNNING' || s === 'SUSPENDED') refresh(true)
  }, 5000)
})
onUnmounted(() => {
  if (refreshTimer) clearTimeout(refreshTimer)
  if (livePoll) clearInterval(livePoll)
})

// ── Graph: resolve node names, types, and the waitForInput prompt ─────────────
const nodeTypes = ref<NodeType[]>([])
const graphStruct = ref<GraphStruct | null>(null)
const graphLoadedFor = ref<string | null>(null)

const GRAPH_QUERY = gql`
  query RelayRunGraph($id: UUID!) {
    pipelines {
      nodeTypes { key label category }
      pipeline(id: $id) { id graph }
    }
  }
`
async function loadGraph(pipelineId: string) {
  if (graphLoadedFor.value === pipelineId) return
  try {
    const res = await gqlQuery<{ pipelines: { nodeTypes: NodeType[]; pipeline: { graph: unknown } | null } }>(
      GRAPH_QUERY, { id: pipelineId })
    nodeTypes.value = res?.pipelines?.nodeTypes ?? []
    const g = (res?.pipelines?.pipeline?.graph ?? {}) as GraphStruct
    graphStruct.value = { nodes: g.nodes ?? [], edges: g.edges ?? [] }
    graphLoadedFor.value = pipelineId
  }
  catch { /* names fall back to the raw nodeId */ }
}
watch(() => run.value?.pipelineId, (id) => { if (id) loadGraph(id) })

const graphNodeById = computed(() => {
  const map = new Map<string, GraphNode>()
  for (const n of graphStruct.value?.nodes ?? []) map.set(n.id, n)
  return map
})

/** One rendered stage: a node's latest status, in execution order, enriched for display + approval. */
interface Stage {
  nodeId: string; name: string; type: string; status: string
  durationMs: number | null; error: string | null
  awaiting: boolean; waitForInput: boolean; prompt: string
}

// Collapse the chronological timeline to the latest status per node, preserving first-seen (execution)
// order. A parked node appears here as SUSPENDED and its id is also in awaitingNodeIds.
const stages = computed<Stage[]>(() => {
  const r = run.value
  if (!r) return []
  const awaiting = new Set(r.awaitingNodeIds ?? [])
  const order: string[] = []
  const latest = new Map<string, NodeExecution>()
  for (const n of r.nodes) {
    if (!latest.has(n.nodeId)) order.push(n.nodeId)
    latest.set(n.nodeId, n)
  }
  const graph = graphStruct.value
  return order.map((nodeId) => {
    const exec = latest.get(nodeId)!
    const gn = graphNodeById.value.get(nodeId)
    const isAwaiting = awaiting.has(nodeId)
    return {
      nodeId,
      name: graph ? resolveNodeName(nodeId, graph, nodeTypes.value) : nodeId,
      type: gn?.type ?? '',
      status: isAwaiting ? 'SUSPENDED' : exec.status,
      durationMs: isAwaiting ? null : exec.durationMs,
      error: exec.error,
      awaiting: isAwaiting,
      waitForInput: gn?.type === 'waitForInput',
      prompt: typeof gn?.prompt === 'string' ? gn.prompt : '',
    }
  })
})

// ── Steps view: the authored milestones + human waits, shown INSTEAD of engine nodes when the
// pipeline declares any. Engine nodes remain the fallback for undecorated pipelines.
const steps = computed<RunStep[]>(() => run.value?.steps ?? [])

const STEP_COLORS: Record<RunStep['status'], string> = {
  PENDING: 'var(--fg-3)',
  RUNNING: 'var(--info, #38bdf8)',
  WAITING: '#ffb547',
  DONE: 'var(--ok, #34d99a)',
  FAILED: 'var(--err, #f87171)',
}

// Rows sit flush — nesting is conveyed by the per-item chip, not indentation.
const displaySteps = computed(() => steps.value)

/** The step's node-type key — nested rows carry it from the backend; depth-0 falls back to the graph. */
function stepNodeType(step: RunStep): string {
  return step.type ?? (step.depth === 0 ? graphNodeById.value.get(step.nodeId)?.type ?? '' : '')
}
/** The run an Approve/Reject must resolve against — a nested row's CHILD run, not the featured run. */
function stepRunId(step: RunStep): string {
  return step.runId ?? props.runId
}
function stepPrompt(step: RunStep): string {
  const prompt = graphNodeById.value.get(step.nodeId)?.prompt
  return typeof prompt === 'string' ? prompt : step.title
}

/** Decide a WAITING Approval Gate inline: approve resumes the run, reject fails it. */
async function decideGate(runId: string, nodeId: string, approved: boolean) {
  submitting[nodeId] = true
  try {
    await mutation(gql`
      mutation ResolveRelayGate($runId: UUID!, $nodeId: String!, $approved: Boolean!) {
        pipelines { resolveGate(runId: $runId, nodeId: $nodeId, approved: $approved) }
      }
    `, { runId, nodeId, approved })
    toast.success(approved ? 'Approved — the run is resuming' : 'Rejected — the run has been failed')
    emit('resumed')
    refresh(true)
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to resolve the gate')
  }
  finally {
    submitting[nodeId] = false
  }
}

// ── Approve / provide input ──────────────────────────────────────────────────
const inputValues = reactive<Record<string, string>>({})
const submitting = reactive<Record<string, boolean>>({})

/** Parse the operator's optional value: empty → `true` (a plain approval); JSON when valid; else the raw string. */
function parseInput(raw: string): unknown {
  const trimmed = raw.trim()
  if (!trimmed) return true
  try { return JSON.parse(trimmed) }
  catch { return trimmed }
}

async function approve(runId: string, nodeId: string) {
  submitting[nodeId] = true
  try {
    await mutation(gql`
      mutation ProvideRelayInput($runId: UUID!, $nodeId: String!, $input: JSON!) {
        pipelines { provideInput(runId: $runId, nodeId: $nodeId, input: $input) }
      }
    `, { runId, nodeId, input: parseInput(inputValues[nodeId] ?? '') })
    toast.success('Input provided — run resumed')
    inputValues[nodeId] = ''
    emit('resumed')
    refresh(true)
  }
  catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to provide input')
  }
  finally {
    submitting[nodeId] = false
  }
}

// ── formatting ───────────────────────────────────────────────────────────────
const STATUS_COLORS: Record<string, string> = {
  OK: 'var(--ok, #34d399)',
  FAILED: 'var(--err, #f87171)',
  SUSPENDED: 'var(--accent, #f59e0b)',
  RUNNING: 'var(--info, #38bdf8)',
  CANCELLED: 'var(--muted, #94a3b8)',
  SKIPPED: 'var(--muted, #94a3b8)',
}
function statusColor(s: string): string { return STATUS_COLORS[s] ?? 'var(--muted, #94a3b8)' }
function statusGlyph(s: string): string {
  switch (s) {
    case 'OK': return '✓'
    case 'FAILED': return '✕'
    case 'SUSPENDED': return '⏸'
    case 'RUNNING': return '●'
    default: return '·'
  }
}
function formatDuration(ms: number | null): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  const sec = Math.round(ms / 1000)
  if (sec < 60) return `${sec}s`
  return `${Math.floor(sec / 60)}m ${sec % 60}s`
}
</script>

<template>
  <div class="run-timeline">
    <div v-if="loading && !run" class="rt-state">Loading run…</div>
    <div v-else-if="!run" class="rt-state">Run not found.</div>
    <template v-else>
      <p v-if="run.error" class="rt-error">{{ run.error }}</p>

      <!-- Steps view: authored milestones + human waits — what an observer reads. Engine nodes only
           appear for pipelines that declare no Status nodes. -->
      <ol v-if="displaySteps.length" class="rt-stages">
        <li
          v-for="(step, i) in displaySteps"
          :key="`${step.nodeId}-${i}`"
          class="rt-stage"
          :class="{ awaiting: step.status === 'WAITING' }">
          <div class="rt-row">
            <span class="rt-step-dot" :style="{ background: STEP_COLORS[step.status] }" />
            <span class="rt-identity">
              <span class="rt-name">{{ step.title }}</span>
            </span>
            <span v-if="step.item" class="rt-item">{{ step.item }}</span>
            <span class="rt-meta" :style="{ color: STEP_COLORS[step.status] }">
              {{ step.status === 'WAITING' ? 'WAITING FOR YOU' : step.status }}
            </span>
          </div>

          <!-- A parked human wait on the run's own graph is answerable right here. -->
          <div v-if="step.status === 'WAITING' && stepNodeType(step) === 'waitForInput'" class="rt-approve">
            <div class="rt-approve-row">
              <TextInput
                v-model="inputValues[step.nodeId]"
                :placeholder="stepPrompt(step) || 'Optional value — blank approves'"
                size="sm" />
              <Button
                primary
                size="sm"
                icon="check-circle"
                :disabled="submitting[step.nodeId]"
                @click="approve(stepRunId(step), step.nodeId)">Approve</Button>
            </div>
          </div>
          <div v-else-if="step.status === 'WAITING' && stepNodeType(step) === 'gate.approval'" class="rt-approve">
            <div class="rt-approve-row">
              <Button
                primary
                size="sm"
                icon="check"
                :disabled="submitting[step.nodeId]"
                @click="decideGate(stepRunId(step), step.nodeId, true)">Approve</Button>
              <Button
                danger
                size="sm"
                icon="x"
                :disabled="submitting[step.nodeId]"
                @click="decideGate(stepRunId(step), step.nodeId, false)">Reject</Button>
            </div>
          </div>
        </li>
      </ol>

      <p v-else-if="!stages.length" class="rt-state">Starting…</p>
      <ol v-else class="rt-stages">
        <li
          v-for="stage in stages"
          :key="stage.nodeId"
          class="rt-stage"
          :class="{ awaiting: stage.awaiting }">
          <div class="rt-row">
            <span class="rt-ic" :style="{ background: statusColor(stage.status), color: 'var(--bg-0, #0c0f14)' }">
              {{ statusGlyph(stage.status) }}
            </span>
            <span class="rt-identity">
              <span class="rt-name">{{ stage.name }}</span>
              <span class="rt-type">
                {{ stage.type || stage.nodeId }}<span
                  v-if="stage.awaiting"
                  class="rt-awaiting"
                > · {{ stage.waitForInput || stage.type === 'gate.approval' ? 'awaiting you' : 'waiting' }}</span>
              </span>
            </span>
            <span v-if="stage.durationMs != null" class="rt-meta tabular">{{ formatDuration(stage.durationMs) }}</span>
            <span v-else-if="stage.awaiting" class="rt-meta" :style="{ color: statusColor('SUSPENDED') }">SUSPENDED</span>
          </div>

          <!-- Human-in-the-loop: approve / supply a value to resume a parked waitForInput node. -->
          <div v-if="stage.awaiting && stage.waitForInput" class="rt-approve">
            <p v-if="stage.prompt" class="rt-prompt">{{ stage.prompt }}</p>
            <div class="rt-approve-row">
              <TextInput
                v-model="inputValues[stage.nodeId]"
                :placeholder="stage.prompt || 'Optional value — blank approves'"
                size="sm" />
              <Button
                primary
                size="sm"
                icon="check-circle"
                :disabled="submitting[stage.nodeId]"
                @click="approve(runId, stage.nodeId)">Approve</Button>
            </div>
          </div>
          <p v-if="stage.error" class="rt-node-error">{{ stage.error }}</p>
        </li>
      </ol>
    </template>
  </div>
</template>

<style scoped>
.run-timeline { display: flex; flex-direction: column; }
.rt-state { padding: 8px 4px; font-size: 13px; color: var(--fg-3); }
.rt-error {
  margin: 0 0 10px; padding: 8px 10px; border-radius: 8px; font-size: 12px;
  background: color-mix(in oklch, var(--err, #f87171) 12%, transparent); color: var(--err, #f87171);
}
.rt-stages { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.rt-stage { padding: 9px 0; border-bottom: 1px dashed var(--line-soft, var(--line)); }
.rt-stage:last-child { border-bottom: 0; }
.rt-stage.awaiting { background: color-mix(in oklch, var(--accent, #f59e0b) 5%, transparent); border-radius: 8px; padding: 9px 10px; }
.rt-row { display: flex; align-items: center; gap: 10px; }
.rt-step-dot { width: 9px; height: 9px; border-radius: 50%; flex: 0 0 9px; }
.rt-item {
  font-size: 10px; color: var(--fg-3); background: var(--bg-2);
  padding: 1px 6px; border-radius: 4px; flex: 0 0 auto;
}
.rt-ic {
  width: 20px; height: 20px; border-radius: 6px; display: grid; place-items: center;
  font-size: 11px; font-weight: 700; flex: 0 0 auto;
}
.rt-identity { display: flex; flex-direction: column; gap: 1px; min-width: 0; flex: 1; }
.rt-name { font-size: 13px; color: var(--fg-0); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rt-type { font-family: var(--font-mono, monospace); font-size: 10.5px; color: var(--fg-3); }
.rt-awaiting { color: var(--accent, #f59e0b); }
.rt-meta { font-family: var(--font-mono, monospace); font-size: 11px; color: var(--fg-3); }
.tabular { font-variant-numeric: tabular-nums; }
.rt-approve { margin: 8px 0 2px 32px; display: flex; flex-direction: column; gap: 8px; }
.rt-prompt { margin: 0; font-size: 12.5px; color: var(--fg-1); }
.rt-approve-row { display: flex; align-items: center; gap: 8px; }
.rt-approve-row :deep(.text-input), .rt-approve-row :deep(input) { flex: 1; }
.rt-node-error { margin: 6px 0 0 32px; font-size: 11px; color: var(--err, #f87171); }
</style>
