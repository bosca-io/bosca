<script setup lang="ts">
interface Module {
  id: string | number
  metadata: { id: string; name: string } | null
}

interface TemplateModule {
  id: string | number
  metadata: { id: string; name: string } | null
}

const props = defineProps<{
  stepId: number
  stepName: string
  stepDate: string | null
  stepMetadataId: string | null
  modules: Module[]
  templateModules: TemplateModule[]
  isCalendar: boolean
  accent?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  addModule: [templateModuleId: number]
  deleteModule: [moduleId: number]
  reorderModules: [moduleIds: number[]]
  openContent: [metadataId: string]
}>()

const router = useRouter()
const addModuleMenuOpen = ref(false)
const deleteModuleId = ref<number | null>(null)

function formatDate(d: string | null): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, {
    weekday: 'short', month: 'short', day: 'numeric', year: 'numeric',
  })
}

function onAddModule(templateModuleId: number) {
  addModuleMenuOpen.value = false
  emit('addModule', templateModuleId)
}

function confirmDeleteModule(moduleId: number) {
  deleteModuleId.value = moduleId
}

function onConfirmDeleteModule() {
  if (deleteModuleId.value != null) {
    emit('deleteModule', deleteModuleId.value)
    deleteModuleId.value = null
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
  const ids = props.modules.map(m => Number(m.id))
  const [moved] = ids.splice(dragIndex, 1)
  ids.splice(dropIndex, 0, moved!)
  emit('reorderModules', ids)
  dragIndex = -1
  dropIndex = -1
}
</script>

<template>
  <div class="step-detail">
    <div class="step-detail-header">
      <span class="step-detail-title">{{ stepName }}</span>
    </div>

    <div class="step-fields">
      <div v-if="isCalendar && stepDate" class="field-row">
        <span class="field-label">Date</span>
        <span class="field-value">{{ formatDate(stepDate) }}</span>
      </div>

      <div v-if="stepMetadataId" class="field-row">
        <span class="field-label">Content</span>
        <Button size="sm" icon="eye" @click="router.push(`/cms/metadata/${stepMetadataId}`)">
          Open Content
        </Button>
      </div>
    </div>

    <!-- Modules -->
    <div class="modules-section">
      <div class="modules-header">
        <span class="modules-title">Modules</span>
        <span class="module-count mono">{{ modules.length }}</span>
        <span style="flex: 1" />
        <div v-if="templateModules.length && !disabled" class="add-wrapper">
          <button class="module-add-btn" title="Add module" @click.stop="addModuleMenuOpen = !addModuleMenuOpen">
            <Icon name="plus" :size="13" />
          </button>
          <div v-if="addModuleMenuOpen" class="add-menu">
            <button
              v-for="tm in templateModules"
              :key="tm.id"
              class="add-menu-item"
              @click="onAddModule(Number(tm.id))"
            >
              {{ tm.metadata?.name ?? `Module ${tm.id}` }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="modules.length" class="module-list" @drop="onDrop">
        <div
          v-for="(mod, i) in modules"
          :key="mod.id"
          class="module-item"
          draggable="true"
          @dragstart="onDragStart($event, i)"
          @dragover="onDragOver($event, i)"
        >
          <span class="drag-handle">⠿</span>
          <span class="module-name">{{ mod.metadata?.name ?? `Module ${mod.id}` }}</span>
          <button
            v-if="mod.metadata"
            class="module-open-btn"
            title="Open module content"
            @click="router.push(`/cms/metadata/${mod.metadata!.id}`)"
          >
            <Icon name="eye" :size="12" color="var(--fg-3)" />
          </button>
          <button
            v-if="!disabled"
            class="module-delete-btn"
            title="Remove module"
            @click.stop="confirmDeleteModule(Number(mod.id))"
          >
            <Icon name="x" :size="12" color="var(--fg-4)" />
          </button>
        </div>
      </div>
      <div v-else class="module-empty">No modules</div>
    </div>

    <ConfirmModal
      v-if="deleteModuleId != null"
      title="Remove Module"
      subtitle="Remove this module from the step?"
      @close="deleteModuleId = null"
      @confirm="onConfirmDeleteModule"
    />
  </div>
</template>

<style scoped>
.step-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.step-detail-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.step-detail-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
}

.step-fields {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.field-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.field-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-3);
  min-width: 80px;
}

.field-value {
  font-size: 13px;
  color: var(--fg-1);
}

.modules-section {
  border-top: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
  padding-top: 14px;
}

.modules-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.modules-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--fg-1);
}

.module-count {
  font-size: 10.5px;
  color: var(--fg-3);
}

.add-wrapper {
  position: relative;
}

.module-add-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  color: var(--fg-2);
  transition: background 0.15s;
}

.module-add-btn:hover {
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

.module-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.module-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--r-sm);
  transition: background 0.15s;
}

.module-item:hover {
  background: color-mix(in oklch, var(--fg-2) 5%, transparent);
}

.drag-handle {
  cursor: grab;
  font-size: 12px;
  color: var(--fg-4);
  user-select: none;
  line-height: 1;
}

.module-name {
  flex: 1;
  font-size: 13px;
  color: var(--fg-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.module-open-btn,
.module-delete-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s;
}

.module-item:hover .module-open-btn,
.module-item:hover .module-delete-btn {
  opacity: 1;
}

.module-open-btn:hover {
  background: var(--bg-3);
}

.module-delete-btn:hover {
  background: color-mix(in oklch, var(--err) 12%, transparent);
}

.module-empty {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--fg-4);
}
</style>
