<script setup lang="ts">
import { NumberInput as BoscaNumberInput } from '@bosca/ui'
import type { FieldNode, JsonSchemaProperty } from '../../types'

defineProps<{
  value: unknown
  node: FieldNode
  propertySchema?: JsonSchemaProperty
  readonly: boolean
  error?: string
}>()

const emit = defineEmits<{
  'update:value': [value: number | null]
}>()
</script>

<template>
  <BoscaNumberInput
    :model-value="(value as number) ?? null"
    :placeholder="node.placeholder ?? ''"
    :disabled="readonly"
    :min="propertySchema?.minimum"
    :max="propertySchema?.maximum"
    @update:model-value="emit('update:value', $event)"
  />

</template>
