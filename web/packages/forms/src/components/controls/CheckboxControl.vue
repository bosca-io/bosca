<script setup lang="ts">
import { Checkbox } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'

defineProps<{
  value: unknown
  node: FieldNode
  propertySchema?: JsonSchemaProperty
  readonly: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:value': [value: boolean]
}>()
</script>

<template>
  <Checkbox
    :model-value="!!value"
    :label="node.label"
    :disabled="readonly"
    @update:model-value="emit('update:value', $event)"
  />
</template>
