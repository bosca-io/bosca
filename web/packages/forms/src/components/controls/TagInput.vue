<script setup lang="ts">
import { computed } from 'vue'
import { TagInput as BoscaTagInput } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'

const props = defineProps<{
  value: unknown
  node: FieldNode
  propertySchema?: JsonSchemaProperty
  readonly: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:value': [value: string[]]
}>()

const tags = computed(() => {
  if (Array.isArray(props.value)) return props.value as string[]
  return []
})
</script>

<template>
  <BoscaTagInput
    :model-value="tags"
    :placeholder="node.placeholder ?? 'Add tags...'"
    :disabled="readonly"
    @update:model-value="emit('update:value', $event)"
  />
</template>
