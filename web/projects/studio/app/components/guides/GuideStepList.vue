<script setup lang="ts">
interface Step {
  id: string | number
  date: string | null
  metadata: { id: string; name: string } | null
  modules: unknown[]
}

interface TemplateStep {
  id: string | number
  metadata: { id: string; name: string } | null
}

const props = defineProps<{
  steps: Step[]
  templateSteps: TemplateStep[]
  selectedIndex: number
  accent?: string
  guideType?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  select: [index: number]
  add: [templateStepId: number]
  delete: [stepId: number]
  reorder: [stepIds: number[]]
}>()

const addMenuOpen = ref(false)
const deleteConfirmId = ref<number | null>(null)
const listEl = ref<HTMLElement>()

const isCalendar = computed(() =>
  props.guideType === 'CALENDAR' || props.guideType === 'CALENDAR_PROGRESS',
)

function formatDate(d: string | null): string {
  if (!d) return ''
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

function onAddStep(templateStepId: number) {
  addMenuOpen.value = false
  emit('add', templateStepId)
}

function confirmDelete(stepId: number) {
  deleteConfirmId.value = stepId
}

function onConfirmDelete() {
  if (deleteConfirmId.value != null) {
    emit('delete', deleteConfirmId.value)
    deleteConfirmId.value = null
  }
}

let dragIndex = -1
let dropIndex = -1

function onDragStart(e: DragEvent, index: number) {
  dragIndex = index
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(index))
  }
}

function onDragOver(e: DragEvent, index: number) {
  e.preventDefault()
  dropIndex = index
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  if (dragIndex < 0 || dropIndex < 0 || dragIndex === dropIndex) return
  const ids = props.steps.map(s => Number(s.id))
  const [moved] = ids.splice(dragIndex, 1)
  ids.splice(dropIndex, 0, moved!)
  emit('reorder', ids)
  dragIndex = -1
  dropIndex = -1
}
</script>

<template>
  <div class="step-list-container">
    <div class="step-list-header">
      <span class="step-list-title">Steps</span>
      <span class="step-count mono">{{ steps.length }}</span>
      <span style="flex: 1" />
      <div v-if="templateSteps.length && !disabled" class="add-wrapper">
        <button class="step-add-btn" title="Add step" @click.stop="addMenuOpen = !addMenuOpen">
          <Icon name="plus" :size="14" />
        </button>
        <div v-if="addMenuOpen" class="add-menu">
          <button
            v-for="ts in templateSteps"
            :key="ts.id"
            class="add-menu-item"
            @click="onAddStep(Number(ts.id))"
          >
            {{ ts.metadata?.name ?? `Step template ${ts.id}` }}
          </button>
        </div>
      </div>
    </div>

    <div ref="listEl" class="step-list" @drop="onDrop">
      <div
        v-for="(step, i) in steps"
        :key="step.id"
        class="step-item"
        :class="{ active: i === selectedIndex }"
        :style="i === selectedIndex ? { borderColor: accent, background: `color-mix(in oklch, ${accent ?? 'var(--brand-2)'} 8%, transparent)` } : {}"
        draggable="true"
        @click="emit('select', i)"
        @dragstart="onDragStart($event, i)"
        @dragover="onDragOver($event, i)"
      >
        <span class="drag-handle" title="Drag to reorder">⠿</span>
        <span class="step-number" :class="{ 'step-number--active': i === selectedIndex }">{{ i + 1 }}</span>
        <div class="step-info">
          <span class="step-name">{{ step.metadata?.name ?? `Step ${i + 1}` }}</span>
          <span v-if="step.date && isCalendar" class="step-date">{{ formatDate(step.date) }}</span>
        </div>
        <button
          v-if="!disabled"
          class="step-delete-btn"
          title="Remove step"
          @click.stop="confirmDelete(Number(step.id))"
        >
          <Icon name="x" :size="12" color="var(--fg-4)" />
        </button>
      </div>
    </div>

    <div v-if="!steps.length" class="step-empty">No steps yet</div>

    <ConfirmModal
      v-if="deleteConfirmId != null"
      title="Remove Step"
      subtitle="Remove this step from the guide? The step content will not be deleted."
      @close="deleteConfirmId = null"
      @confirm="onConfirmDelete"
    />
  </div>
</template>

<style scoped>
.step-list-container {
  display: flex;
  flex-direction: column;
}

.step-list-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.step-list-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.step-count {
  font-size: 10.5px;
  color: var(--fg-3);
}

.add-wrapper {
  position: relative;
}

.step-add-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  color: var(--fg-2);
  transition: background 0.15s;
}

.step-add-btn:hover {
  background: var(--bg-3);
}

.add-menu {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 4px;
  min-width: 180px;
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.35);
  padding: 3px;
  z-index: 20;
}

.add-menu-item {
  width: 100%;
  display: flex;
  align-items: center;
  padding: 7px 10px;
  font-size: 13px;
  color: var(--fg-1);
  border-radius: var(--r-sm);
  text-align: left;
  transition: background 0.15s;
}

.add-menu-item:hover {
  background: var(--bg-2);
}

.step-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.step-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border-radius: var(--r-sm);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.step-item:hover {
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
}

.drag-handle {
  cursor: grab;
  font-size: 12px;
  color: var(--fg-4);
  user-select: none;
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

.step-number--active {
  background: var(--brand-2);
  color: #fff;
}

.step-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.step-name {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-date {
  font-size: 10.5px;
  color: var(--fg-3);
}

.step-delete-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s;
}

.step-item:hover .step-delete-btn {
  opacity: 1;
}

.step-delete-btn:hover {
  background: color-mix(in oklch, var(--err) 12%, transparent);
}

.step-empty {
  padding: 20px;
  text-align: center;
  font-size: 12px;
  color: var(--fg-3);
}
</style>
