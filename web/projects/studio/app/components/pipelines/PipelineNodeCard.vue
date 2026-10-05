<script setup lang="ts">
/**
 * Canvas card for one pipeline node. Category drives the accent + which connection handles exist:
 * INPUT has no inbound, OUTPUT has no outbound; everything else has both (actions like
 * Execute Script can produce an output consumed downstream).
 */
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{
  data: {
    kind: string
    label: string
    category: string
    settings?: Record<string, unknown>
    outputs?: { name: string, kind: string, error: boolean }[]
    inputs?: { name: string, typeLabel: string, description: string | null, required: boolean }[]
    /**
     * Outcome for this node, painted onto the canvas. The editor's dry-run uses the static three
     * (`ran` / `skipped` / `error`); a live run adds `running` (executing now) and `awaiting`
     * (suspended on out-of-band work) so the graph advances with the run.
     */
    runState?: 'ran' | 'skipped' | 'error' | 'running' | 'awaiting'
  }
  selected?: boolean
  /** The pipeline this node references (a For Each body, a Run Pipeline target) — shows the corner peek button. */
  peekPipelineId?: string | null
}>()
const emit = defineEmits<{ peek: [pipelineId: string] }>()

/** Tooltip on the single (anonymous) input handle, for nodes that declare no named input slots. */
const inputTooltip = computed(() => {
  const slots = props.data.inputs ?? []
  if (slots.length === 0) return undefined
  return 'Expects ' + slots
    .map(s => `${s.typeLabel}${s.required ? '' : ' (optional)'}${s.description ? ` — ${s.description}` : ''}`)
    .join(' · ')
})

// A node with MORE THAN ONE declared input slot renders one target handle each (mirroring the named
// output ports), so each inbound edge attaches to a specific port by the handle it lands on. Single-
// input and free-form nodes (e.g. Objects → Map, whose port names are operator-invented) keep the
// single anonymous handle — their inbound edges carry no per-slot handle, exactly as before.
const declaredInputs = computed(() => props.data.inputs ?? [])
const hasMultipleInputs = computed(() => declaredInputs.value.length > 1)
function inputTitle(s: { typeLabel: string, description: string | null, required: boolean }): string {
  return `${s.typeLabel}${s.required ? '' : ' (optional)'}${s.description ? ` — ${s.description}` : ''}`
}

// Operator-given name wins; the node type's label is the fallback (and the subtitle when named).
const displayName = computed(() => {
  const name = props.data.settings?.name
  return typeof name === 'string' && name.trim().length > 0 ? name : props.data.label
})
const showTypeSubtitle = computed(() => displayName.value !== props.data.label)

// Shared with the palette and Node Browser so a node's kind reads the same hue everywhere.
const accent = computed(() => pipelineNodeAccent(props.data.category))

// Routing nodes emit on labeled branches instead of the single source handle.
const isRouting = computed(() => props.data.category === 'ROUTE')

// Node types that declare named output ports (incl. error ports) render one source handle each.
// A Switch node's ports are dynamic — one per case (in order) plus the default — so they are derived
// from its live settings rather than a static descriptor, keeping the handles in sync as cases edit.
const declaredOutputs = computed(() => {
  if (props.data.kind === 'switch') {
    const settings = props.data.settings ?? {}
    const cases = Array.isArray(settings.cases) ? settings.cases : []
    const caseHandles = cases
      .map(c => (c as { label?: unknown }).label)
      .filter((l): l is string => typeof l === 'string' && l.trim().length > 0)
      .map(name => ({ name, kind: 'ANY', error: false }))
    const def = typeof settings.defaultLabel === 'string' && settings.defaultLabel.trim().length > 0
      ? settings.defaultLabel.trim()
      : 'default'
    return [...caseHandles, { name: def, kind: 'ANY', error: false }]
  }
  return props.data.outputs ?? []
})
// A node renders NAMED output handles when it has more than one port, or any error port, or is a
// Switch (dynamic case ports). A lone non-error output is the node's single implicit value and renders
// as the anonymous handle below — so a typed single-output node stays as clean as an untyped one.
const hasNamedPorts = computed(() => {
  if (props.data.kind === 'switch') return true
  const outs = declaredOutputs.value
  return outs.length > 1 || outs.some(o => o.error)
})
/** Even vertical placement for output handle `i` of `n` down the card's right edge. */
function handleTop(i: number, n: number): string {
  return `${Math.round(((i + 1) / (n + 1)) * 100)}%`
}

const icon = computed(() => pipelineNodeIcon(props.data.kind, props.data.category))
</script>

<template>
  <div
    class="node-card"
    :class="{ selected, ran: data.runState === 'ran', skipped: data.runState === 'skipped', errored: data.runState === 'error', running: data.runState === 'running', awaiting: data.runState === 'awaiting' }"
    :style="{ '--accent': accent }"
    :title="typeof data.settings?.description === 'string' ? data.settings.description : undefined"
  >
    <!-- Inbound ports: one handle+label per declared input slot, or a single anonymous handle for
         free-form nodes. INPUT nodes have no inbound. -->
    <template v-if="data.category !== 'INPUT'">
      <template v-if="hasMultipleInputs">
        <template
          v-for="(s, i) in declaredInputs"
          :key="s.name"
        >
          <Handle
            :id="s.name"
            type="target"
            :position="Position.Left"
            :style="{ top: handleTop(i, declaredInputs.length) }"
            :title="inputTitle(s)"
          />
          <span
            class="port-label port-label-in"
            :style="{ top: handleTop(i, declaredInputs.length) }"
          >{{ s.name }}</span>
        </template>
      </template>
      <Handle
        v-else
        type="target"
        :position="Position.Left"
        :title="inputTooltip"
      />
    </template>
    <!-- Corner peek into the referenced pipeline. `nodrag`/`nopan` plus the stop modifiers keep the
         press from starting a node drag or a canvas pan, and the click from selecting the node. -->
    <button
      v-if="peekPipelineId"
      type="button"
      class="peek-corner nodrag nopan"
      title="Peek into the referenced pipeline"
      @click.stop="emit('peek', peekPipelineId)"
      @mousedown.stop
      @pointerdown.stop
    >
      <Icon
        name="eye"
        :size="11"
      />
    </button>
    <div class="node-head">
      <Icon
        :name="icon"
        :size="16"
        class="node-icon"
      />
      <div class="node-text">
        <div class="category">
          {{ data.category }}
        </div>
        <div class="label">
          {{ displayName }}
        </div>
        <div
          v-if="showTypeSubtitle"
          class="type-subtitle"
        >
          {{ data.label }}
        </div>
      </div>
    </div>
    <!-- Each named output port renders its handle plus a label just outside the right edge, so a
         builder can tell `out` from `alreadyExists`/`error` without hovering each dot. -->
    <template v-if="hasNamedPorts">
      <template
        v-for="(o, i) in declaredOutputs"
        :key="o.name"
      >
        <Handle
          :id="o.name"
          type="source"
          :position="Position.Right"
          :class="o.error ? 'handle-error' : 'handle-out'"
          :style="{ top: handleTop(i, declaredOutputs.length) }"
          :title="o.error ? `error: ${o.name}` : o.name"
        />
        <span
          class="port-label"
          :class="{ 'port-label-error': o.error }"
          :style="{ top: handleTop(i, declaredOutputs.length) }"
        >{{ o.name }}</span>
      </template>
    </template>
    <template v-else-if="isRouting">
      <Handle
        id="true"
        type="source"
        :position="Position.Right"
        class="handle-true"
        title="true branch"
      />
      <span class="port-label port-label-true">true</span>
      <Handle
        id="false"
        type="source"
        :position="Position.Right"
        class="handle-false"
        title="false branch"
      />
      <span class="port-label port-label-false">false</span>
    </template>
    <Handle
      v-else-if="data.category !== 'OUTPUT'"
      type="source"
      :position="Position.Right"
    />
  </div>
</template>

<style scoped>
.node-card {
  min-width: 140px;
  padding: 8px 12px;
  border: 1px solid color-mix(in srgb, var(--accent) 50%, transparent);
  border-left: 3px solid var(--accent);
  border-radius: 8px;
  background: var(--surface-raised, rgba(15, 23, 42, 0.9));
  font-size: 12px;
}
.node-card.selected {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
}
/* Dry-run path: ran nodes glow, skipped (not-taken branch) nodes fade, errored nodes flag red. */
.node-card.ran {
  border-color: color-mix(in oklch, var(--ok, #34d399) 70%, transparent);
  box-shadow: 0 0 0 2px color-mix(in oklch, var(--ok, #34d399) 45%, transparent);
}
.node-card.skipped {
  opacity: 0.4;
  filter: saturate(0.35);
}
.node-card.errored {
  border-color: color-mix(in oklch, var(--danger, #f87171) 75%, transparent);
  box-shadow: 0 0 0 2px color-mix(in oklch, var(--danger, #f87171) 50%, transparent);
}
/* Live run: the executing node pulses in info-blue; a suspended node holds steady in amber. */
.node-card.running {
  border-color: color-mix(in oklch, var(--info, #38bdf8) 80%, transparent);
  animation: node-running-pulse 1.4s ease-in-out infinite;
}
@keyframes node-running-pulse {
  0%, 100% { box-shadow: 0 0 0 2px color-mix(in oklch, var(--info, #38bdf8) 25%, transparent); }
  50% { box-shadow: 0 0 0 4px color-mix(in oklch, var(--info, #38bdf8) 55%, transparent); }
}
.node-card.awaiting {
  border-color: color-mix(in oklch, var(--warning, #fbbf24) 80%, transparent);
  box-shadow: 0 0 0 2px color-mix(in oklch, var(--warning, #fbbf24) 45%, transparent);
}
/* Respect reduced-motion: keep the running highlight, drop the pulse. */
@media (prefers-reduced-motion: reduce) {
  .node-card.running {
    animation: none;
    box-shadow: 0 0 0 2px color-mix(in oklch, var(--info, #38bdf8) 45%, transparent);
  }
}
/* Make the connection handles clearly visible and easy to grab on the dark canvas. */
.node-card :deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid var(--surface-raised, rgba(15, 23, 42, 0.9));
  background: var(--accent);
}
/* Condition branches: green = true (upper), red = false (lower). */
.node-card :deep(.vue-flow__handle.handle-true) {
  top: 32%;
  background: var(--success, #34d399);
}
.node-card :deep(.vue-flow__handle.handle-false) {
  top: 72%;
  background: var(--danger, #f87171);
}
/* Declared output ports: error ports are red, success/other ports take the card accent. */
.node-card :deep(.vue-flow__handle.handle-error) {
  background: var(--danger, #f87171);
}
.node-card :deep(.vue-flow__handle.handle-out) {
  background: var(--accent);
}
/* Port name shown beside each output handle, just past the card's right edge. Non-interactive so
   it never intercepts a drag from the handle; a subtle pill keeps it legible over crossing edges. */
.port-label {
  position: absolute;
  left: 100%;
  margin-left: 10px;
  transform: translateY(-50%);
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--surface-raised, rgba(15, 23, 42, 0.9));
  color: var(--text-muted, #94a3b8);
  font-size: 9px;
  line-height: 1.4;
  white-space: nowrap;
  pointer-events: none;
}
.port-label-error {
  color: var(--danger, #f87171);
}
/* Input port labels sit just outside the LEFT edge (mirror of the right-edge output labels). */
.port-label-in {
  left: auto;
  right: 100%;
  margin-left: 0;
  margin-right: 10px;
}
/* Condition branch labels track their handle positions (32% / 72%) and colors. */
.port-label-true {
  top: 32%;
  color: var(--success, #34d399);
}
.port-label-false {
  top: 72%;
  color: var(--danger, #f87171);
}
/* Anchored to the node wrapper (the card's exact bounds), like the port labels and handles —
   .node-card stays unpositioned so their containing block doesn't change. `pointer-events: auto`
   opts the button back in on read-only canvases, where Vue Flow puts `pointer-events: none` on the
   whole node wrapper (a node that is neither draggable, selectable, nor connectable). */
.peek-corner {
  position: absolute;
  top: 3px;
  right: 3px;
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--text-muted, #94a3b8);
  cursor: pointer;
}
.peek-corner:hover {
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 16%, transparent);
}
.node-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.node-icon {
  flex-shrink: 0;
  margin-top: 2px;
  color: var(--accent);
}
.node-text {
  min-width: 0;
}
.category {
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--accent);
}
.label {
  margin-top: 2px;
  color: var(--text, #e2e8f0);
  font-weight: 500;
}
.type-subtitle {
  font-size: 10px;
  color: var(--text-muted, #94a3b8);
}
</style>
