<script setup lang="ts">
import { ref, computed } from 'vue'
import Icon from './Icon.vue'

withDefaults(defineProps<{
  label?: string
  accept?: string
  multiple?: boolean
  disabled?: boolean
  maxSizeMb?: number
}>(), {
  maxSizeMb: 50,
})

const emit = defineEmits<{
  files: [files: File[]]
}>()

const dragging = ref(false)
const error = ref('')
const inputEl = ref<HTMLInputElement>()

function handleFiles(fileList: FileList | null) {
  if (!fileList?.length) return
  error.value = ''
  const files = Array.from(fileList)
  emit('files', files)
}

function onDrop(e: DragEvent) {
  dragging.value = false
  handleFiles(e.dataTransfer?.files ?? null)
}

function onInputChange(e: Event) {
  handleFiles((e.target as HTMLInputElement).files)
  if (inputEl.value) inputEl.value.value = ''
}

const dropzoneClass = computed(() => ({
  'dropzone': true,
  'dragging': dragging.value,
  'disabled': false,
}))
</script>

<template>
  <div class="file-upload-root" :class="{ disabled }">
    <label v-if="label" class="file-upload-label">{{ label }}</label>
    <div
      :class="dropzoneClass"
      @dragenter.prevent="dragging = true"
      @dragover.prevent
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
      @click="inputEl?.click()"
    >
      <Icon name="upload" :size="20" color="var(--fg-3)" />
      <span class="dropzone-text">
        Drop files here or <span class="dropzone-link">browse</span>
      </span>
      <span v-if="accept" class="dropzone-hint">{{ accept }}</span>
    </div>
    <p v-if="error" class="file-upload-error">{{ error }}</p>
    <input
      ref="inputEl"
      type="file"
      class="file-upload-hidden"
      :accept
      :multiple
      @change="onInputChange"
    />
  </div>
</template>

<style scoped>
.file-upload-root {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.file-upload-root.disabled {
  opacity: 0.5;
  pointer-events: none;
}

.file-upload-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}

.dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
  border: 2px dashed var(--line-2);
  border-radius: var(--r-md);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.dropzone:hover,
.dropzone.dragging {
  border-color: var(--brand-2);
  background: color-mix(in oklch, var(--brand-2) 6%, transparent);
}

.dropzone-text {
  font-size: 13px;
  color: var(--fg-2);
}

.dropzone-link {
  color: var(--brand-2);
  text-decoration: underline;
  font-weight: 500;
}

.dropzone-hint {
  font-size: 11px;
  color: var(--fg-3);
}

.file-upload-error {
  margin: 0;
  font-size: 12px;
  color: var(--err);
}

.file-upload-hidden {
  display: none;
}
</style>
