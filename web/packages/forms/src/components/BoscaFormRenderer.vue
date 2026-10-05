<script setup lang="ts">
import type { JsonSchema, UiSchema, UiSchemaNode } from '../types'
import type { ValidationErrors } from '../validation'
import FormSection from './layout/FormSection.vue'
import FormRow from './layout/FormRow.vue'
import FormField from './controls/FormField.vue'
import AlertDisplay from './display/AlertDisplay.vue'
import DividerDisplay from './display/DividerDisplay.vue'
import HeadingDisplay from './display/HeadingDisplay.vue'
import HelpText from './display/HelpText.vue'

defineProps<{
  schema: JsonSchema
  uiSchema: UiSchema
  modelValue: Record<string, unknown>
  readonly: boolean
  errors: ValidationErrors
}>()

const emit = defineEmits<{
  'update:field': [property: string, value: unknown]
}>()

function getDisplayComponent(control: string) {
  switch (control) {
    case 'alert': return AlertDisplay
    case 'divider': return DividerDisplay
    case 'heading': return HeadingDisplay
    case 'help-text': return HelpText
    default: return null
  }
}
</script>

<template>
  <div class="bosca-form-renderer">
    <template v-for="(node, index) in uiSchema.layout" :key="index">
      <FormSection
        v-if="node.type === 'section'"
        :node="node"
        :schema="schema"
        :model-value="modelValue"
        :readonly="readonly"
        :errors="errors"
        @update:field="(prop, val) => emit('update:field', prop, val)"
      />

      <FormRow
        v-else-if="node.type === 'row'"
        :node="node"
        :schema="schema"
        :model-value="modelValue"
        :readonly="readonly"
        :errors="errors"
        @update:field="(prop, val) => emit('update:field', prop, val)"
      />

      <FormField
        v-else-if="node.type === 'field'"
        :node="node"
        :schema="schema"
        :model-value="modelValue"
        :readonly="readonly"
        :errors="errors"
        @update:field="(prop, val) => emit('update:field', prop, val)"
      />

      <component
        v-else-if="node.type === 'display' && getDisplayComponent(node.control)"
        :is="getDisplayComponent(node.control)"
        :node="node"
      />
    </template>
  </div>
</template>

<style scoped>
.bosca-form-renderer {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
</style>
