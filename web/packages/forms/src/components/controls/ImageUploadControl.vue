<script setup lang="ts">
import { ref } from 'vue'
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

const preview = ref<string | null>(null)

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  const reader = new FileReader()
  reader.onload = (e) => {
    preview.value = e.target?.result as string
    // In a real implementation, this would upload the file and emit the metadata ID
    emit('update:value', preview.value)
  }
  reader.readAsDataURL(file)
}

function clear() {
  preview.value = null
  emit('update:value', null)
}
</script>

<template>
  <div>
    <div v-if="(value || preview)" class="mb-2">
      <img
        :src="(preview ?? value) as string"
        alt="Preview"
        class="h-24 w-24 rounded-md border border-default object-cover"
      />
      <button
        v-if="!readonly"
        type="button"
        class="mt-1 text-xs text-red-500 hover:text-red-700"
        @click="clear"
      >
        Remove
      </button>
    </div>

    <input
      v-if="!readonly"
      type="file"
      :accept="node.accept ?? 'image/*'"
      class="text-sm"
      @change="handleFileChange"
    />
  </div>
</template>
