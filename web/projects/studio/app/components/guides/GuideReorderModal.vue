<script setup lang="ts">
interface Step {
  id: number
  metadata: { id: string; name: string } | null
}

const props = defineProps<{
  steps: Step[]
  loading?: boolean
}>()

const emit = defineEmits<{
  close: []
  save: [stepIds: number[]]
}>()

const localSteps = ref<Step[]>([...props.steps])

let dragIndex = -1
let overIndex = -1
const draggingIdx = ref(-1)
const dragOverIdx = ref(-1)

function onDragStart(e: DragEvent, index: number) {
  dragIndex = index
  draggingIdx.value = index
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(index))
  }
}

function onDragOver(e: DragEvent, index: number) {
  e.preventDefault()
  overIndex = index
  dragOverIdx.value = index
}

function onDragEnd() {
  draggingIdx.value = -1
  dragOverIdx.value = -1
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  if (dragIndex < 0 || overIndex < 0 || dragIndex === overIndex) {
    onDragEnd()
    return
  }
  const items = [...localSteps.value]
  const [moved] = items.splice(dragIndex, 1)
  items.splice(overIndex, 0, moved!)
  localSteps.value = items
  dragIndex = -1
  overIndex = -1
  onDragEnd()
}

function onSave() {
  emit('save', localSteps.value.map(s => s.id))
}
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click="emit('close')">
      <div class="reorder-box" @click.stop>
        <div class="reorder-header">
          <span class="reorder-icon">
            <Icon name="list" :size="14" color="var(--fg-1)" />
          </span>
          <div class="reorder-header-text">
            <div class="reorder-title">Reorder Steps</div>
            <div class="reorder-subtitle">Drag steps to rearrange their order</div>
          </div>
        </div>

        <div class="reorder-body" @drop="onDrop">
          <div
            v-for="(step, i) in localSteps"
            :key="step.id"
            class="reorder-item"
            :class="{
              'reorder-item--dragging': draggingIdx === i,
              'reorder-item--over': dragOverIdx === i && draggingIdx !== i,
            }"
            draggable="true"
            @dragstart="onDragStart($event, i)"
            @dragover="onDragOver($event, i)"
            @dragend="onDragEnd"
          >
            <span class="drag-handle" title="Drag to reorder">⠿</span>
            <span class="step-number">{{ i + 1 }}</span>
            <span class="step-name">{{ step.metadata?.name ?? `Step ${i + 1}` }}</span>
          </div>
          <div v-if="!localSteps.length" class="reorder-empty">No steps to reorder</div>
        </div>

        <div class="reorder-footer">
          <span class="spacer" />
          <Button size="sm" @click="emit('close')">Cancel</Button>
          <Button
            size="sm"
            primary
            :disabled="loading"
            @click="onSave">
            {{ loading ? 'Saving…' : 'Save Order' }}
          </Button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: color-mix(in oklch, #000 55%, transparent);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.reorder-box {
  width: min(440px, 100%);
  max-height: 80vh;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: 0 24px 60px -20px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.reorder-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.reorder-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  flex: 0 0 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-3);
  border: 1px solid var(--line);
}

.reorder-header-text { flex: 1; }
.reorder-title { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.reorder-subtitle { font-size: 11.5px; color: var(--fg-3); }

.reorder-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.reorder-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  border: 1px solid var(--line);
  background: var(--bg-1);
  cursor: grab;
  transition: background 0.15s, border-color 0.15s, opacity 0.15s;
  user-select: none;
}

.reorder-item:hover {
  background: var(--bg-2);
}

.reorder-item--dragging {
  opacity: 0.4;
}

.reorder-item--over {
  border-color: var(--brand-2);
  background: color-mix(in oklch, var(--brand-2) 8%, transparent);
}

.drag-handle {
  font-size: 14px;
  color: var(--fg-4);
  line-height: 1;
}

.step-number {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--bg-3);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10.5px;
  font-weight: 600;
  color: var(--fg-2);
  flex-shrink: 0;
}

.step-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reorder-empty {
  padding: 24px;
  text-align: center;
  font-size: 12px;
  color: var(--fg-3);
}

.reorder-footer {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  background: var(--bg-2);
  display: flex;
  align-items: center;
  gap: 10px;
}

.spacer { flex: 1; }
</style>
