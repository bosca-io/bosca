<script setup lang="ts">
import type { FieldNode, JsonSchemaProperty } from '../../types'

defineProps<{
  value: unknown
  node: FieldNode
  propertySchema?: JsonSchemaProperty
  readonly: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:value': [value: string]
}>()
</script>

<template>
  <UColorPicker
    :model-value="(value as string) ?? '#000000'"
    :disabled="readonly"
    @update:model-value="emit('update:value', $event)"
  />
</template>
