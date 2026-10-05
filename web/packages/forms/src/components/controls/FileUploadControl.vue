<script setup lang="ts">
import { ref, computed } from 'vue'
import type { FieldNode, JsonSchemaProperty } from '../../types'

defineProps<{
  value: unknown
  node: FieldNode
  propertySchema?: JsonSchemaProperty
  readonly: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:value': [value: string | null]
}>()

const fileName = ref<string | null>(null)

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  fileName.value = file.name
  const reader = new FileReader()
  reader.onload = (e) => {
    // In a real implementation, this would upload the file and emit the metadata ID
    emit('update:value', e.target?.result as string)
  }
  reader.readAsDataURL(file)
}

function clear() {
  fileName.value = null
  emit('update:value', null)
}
</script>

<template>
  <div>
    <div v-if="fileName" class="mb-2 flex items-center gap-2">
      <span class="text-sm">{{ fileName }}</span>
      <button
        v-if="!readonly"
        type="button"
        class="text-xs text-red-500 hover:text-red-700"
        @click="clear"
      >
        Remove
      </button>
    </div>

    <input
      v-if="!readonly"
      type="file"
      :accept="node.accept ?? '*'"
      class="text-sm"
      @change="handleFileChange"
    />
  </div>
</template>
