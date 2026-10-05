<script setup lang="ts">
const props = withDefaults(defineProps<{
  title?: string
  description?: string
  compact?: boolean
  disabled?: boolean
}>(), {
  title: 'Drop raw artifact files here',
  description: 'or click to choose files',
  compact: false,
  disabled: false,
})

const emit = defineEmits<{
  files: [files: File[]]
}>()

const input = ref<HTMLInputElement | null>(null)
const dragging = ref(false)

function chooseFiles() {
  if (!props.disabled) input.value?.click()
}

function emitFiles(list: FileList | null) {
  if (props.disabled || !list?.length) return
  emit('files', Array.from(list))
}

function onInput(event: Event) {
  const target = event.target as HTMLInputElement
  emitFiles(target.files)
  target.value = ''
}

function onDragEnter(event: DragEvent) {
  event.preventDefault()
  if (props.disabled) return
  dragging.value = true
}

function onDragOver(event: DragEvent) {
  event.preventDefault()
  if (props.disabled) return
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}

function onDragLeave(event: DragEvent) {
  const next = event.relatedTarget
  if (next instanceof Node && (event.currentTarget as HTMLElement).contains(next)) return
  dragging.value = false
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  if (props.disabled) return
  dragging.value = false
  emitFiles(event.dataTransfer?.files ?? null)
}
</script>

<template>
  <div
    class="raw-drop-zone"
    :class="{ dragging, compact, disabled }"
    role="button"
    :tabindex="disabled ? -1 : 0"
    :aria-disabled="disabled"
    @click="chooseFiles"
    @keydown.enter.prevent="chooseFiles"
    @keydown.space.prevent="chooseFiles"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <input
      ref="input"
      class="file-input"
      type="file"
      multiple
      @change="onInput"
    >
    <span class="drop-icon"><Icon name="upload" :size="compact ? 16 : 20" color="var(--fg-3)" /></span>
    <span class="drop-copy">
      <strong>{{ title }}</strong>
      <span>{{ description }}</span>
    </span>
  </div>
</template>

<style scoped>
.raw-drop-zone {
  display: flex;
  min-height: 116px;
  align-items: center;
  justify-content: center;
  gap: 13px;
  padding: 20px;
  border: 1px dashed color-mix(in oklch, var(--fg-3) 45%, transparent);
  border-radius: var(--r-sm);
  background: color-mix(in oklch, var(--fg-3) 4%, transparent);
  cursor: pointer;
  outline: none;
  transition: border-color 0.15s, background 0.15s, transform 0.15s;
}

.raw-drop-zone:hover,
.raw-drop-zone:focus-visible,
.raw-drop-zone.dragging {
  border-color: var(--accent, #c084fc);
  background: color-mix(in oklch, var(--accent, #c084fc) 8%, transparent);
}

.raw-drop-zone.dragging { transform: translateY(-1px); }
.raw-drop-zone.compact { min-height: 76px; justify-content: flex-start; padding: 14px 16px; }
.raw-drop-zone.disabled { cursor: not-allowed; opacity: 0.55; }

.file-input { display: none; }

.drop-icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: var(--r-xs);
  background: color-mix(in oklch, var(--fg-3) 9%, transparent);
}

.drop-copy { display: flex; flex-direction: column; gap: 3px; }
.drop-copy strong { color: var(--fg-1); font-size: 13px; font-weight: 600; }
.drop-copy span { color: var(--fg-3); font-size: 11.5px; }
</style>
