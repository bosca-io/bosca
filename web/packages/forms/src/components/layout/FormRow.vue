<script setup lang="ts">
import type { RowNode, JsonSchema } from '../../types'
import type { ValidationErrors } from '../../validation'
import FormField from '../controls/FormField.vue'

defineProps<{
  node: RowNode
  schema: JsonSchema
  modelValue: Record<string, unknown>
  readonly: boolean
  errors: ValidationErrors
}>()

const emit = defineEmits<{
  'update:field': [property: string, value: unknown]
}>()
</script>

<template>
  <div class="bosca-form-row">
    <template v-for="(child, index) in node.children" :key="index">
      <div
        v-if="child.type === 'field'"
        :style="{ gridColumn: `span ${(child as any).col || 12}` }"
      >
        <FormField
          :node="child"
          :schema="schema"
          :model-value="modelValue"
          :readonly="readonly"
          :errors="errors"
          @update:field="(prop, val) => emit('update:field', prop, val)"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.bosca-form-row {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 14px;
}
</style>
