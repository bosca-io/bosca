<script setup lang="ts">
import type { SelectOption } from '@bosca/ui'

interface Module {
  id: number
  metadata: { id: string; name: string } | null
}

const props = defineProps<{
  stepName: string
  modules: Module[]
  templateModules: Array<{ id: number; name: string }>
  loading?: boolean
}>()

const emit = defineEmits<{
  close: []
  add: [templateModuleId: number]
  delete: [moduleId: number]
  save: [moduleIds: number[]]
}>()

const localModules = ref<Module[]>([...props.modules])
watch(() => props.modules, (m) => { localModules.value = [...m] })

const orderChanged = computed(() =>
  localModules.value.some((m, i) => m.id !== props.modules[i]?.id))

const templateOptions = computed<SelectOption[]>(() =>
  props.templateModules.map(m => ({ value: String(m.id), label: m.name })))

const addModuleId = ref('')

function onAdd() {
  if (!addModuleId.value) return
  emit('add', Number(addModuleId.value))
  addModuleId.value = ''
}

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
  const items = [...localModules.value]
  const [moved] = items.splice(dragIndex, 1)
  items.splice(overIndex, 0, moved!)
  localModules.value = items
  dragIndex = -1
  overIndex = -1
  onDragEnd()
}

function onSaveOrder() {
  emit('save', localModules.value.map(m => m.id))
}
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click="emit('close')">
      <div class="modules-box" @click.stop>
        <div class="modules-header">
          <span class="modules-icon">
            <Icon name="layers" :size="14" color="var(--fg-1)" />
          </span>
          <div class="modules-header-text">
            <div class="modules-title">Step Modules</div>
            <div class="modules-subtitle">{{ stepName }}</div>
          </div>
        </div>

        <div v-if="templateOptions.length" class="modules-add">
          <Select
            v-model="addModuleId"
            :options="templateOptions"
            placeholder="Add module from template…"
            size="sm" />
          <Button
            size="sm"
            icon="plus"
            primary
            :disabled="!addModuleId || loading"
            @click="onAdd">
            Add
          </Button>
        </div>

        <div class="modules-body" @drop="onDrop">
          <div
            v-for="(module, i) in localModules"
            :key="module.id"
            class="modules-item"
            :class="{
              'modules-item--dragging': draggingIdx === i,
              'modules-item--over': dragOverIdx === i && draggingIdx !== i,
            }"
            draggable="true"
            @dragstart="onDragStart($event, i)"
            @dragover="onDragOver($event, i)"
            @dragend="onDragEnd"
          >
            <span class="drag-handle" title="Drag to reorder">⠿</span>
            <span class="module-number">{{ i + 1 }}</span>
            <span class="module-name">{{ module.metadata?.name ?? `Module ${i + 1}` }}</span>
            <button
              class="module-delete"
              title="Delete module"
              :disabled="loading"
              @click="emit('delete', module.id)">
              <Icon name="trash" :size="12" color="var(--fg-3)" />
            </button>
          </div>
          <div v-if="!localModules.length" class="modules-empty">No modules in this step</div>
        </div>

        <div class="modules-footer">
          <span class="spacer" />
          <Button size="sm" @click="emit('close')">Close</Button>
          <Button
            v-if="localModules.length > 1"
            size="sm"
            primary
            :disabled="loading || !orderChanged"
            @click="onSaveOrder">
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

.modules-box {
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

.modules-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.modules-icon {
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

.modules-header-text { flex: 1; }
.modules-title { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.modules-subtitle { font-size: 11.5px; color: var(--fg-3); }

.modules-add {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 12px 0;
}

.modules-add :deep(.select) { flex: 1; }

.modules-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.modules-item {
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

.modules-item:hover { background: var(--bg-2); }
.modules-item--dragging { opacity: 0.4; }

.modules-item--over {
  border-color: var(--brand-2);
  background: color-mix(in oklch, var(--brand-2) 8%, transparent);
}

.drag-handle {
  font-size: 14px;
  color: var(--fg-4);
  line-height: 1;
}

.module-number {
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

.module-name {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.module-delete {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-xs);
  background: none;
  border: none;
  cursor: pointer;
  flex-shrink: 0;
}

.module-delete:hover { background: color-mix(in oklch, var(--err) 12%, transparent); }
.module-delete:disabled { opacity: 0.4; cursor: not-allowed; }

.modules-empty {
  padding: 24px;
  text-align: center;
  font-size: 12px;
  color: var(--fg-3);
}

.modules-footer {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  background: var(--bg-2);
  display: flex;
  align-items: center;
  gap: 10px;
}

.spacer { flex: 1; }
</style>
