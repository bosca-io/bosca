<script setup lang="ts">
import { DateInput } from '@bosca/ui'
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
  <DateInput
    :model-value="(value as string) ?? ''"
    type="date"
    :disabled="readonly"
    @update:model-value="emit('update:value', $event)"
  />
</template>
