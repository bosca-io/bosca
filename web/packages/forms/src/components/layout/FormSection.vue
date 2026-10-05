<script setup lang="ts">
import type { SectionNode, JsonSchema } from '../../types'
import type { ValidationErrors } from '../../validation'
import FormRow from './FormRow.vue'
import FormField from '../controls/FormField.vue'
import AlertDisplay from '../display/AlertDisplay.vue'
import DividerDisplay from '../display/DividerDisplay.vue'
import HeadingDisplay from '../display/HeadingDisplay.vue'
import HelpText from '../display/HelpText.vue'

defineProps<{
  node: SectionNode
  schema: JsonSchema
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
  <fieldset class="bosca-form-section">
    <legend v-if="node.label" class="section-label">
      {{ node.label }}
    </legend>

    <div class="section-body">
      <template v-for="(child, index) in node.children" :key="index">
        <FormSection
          v-if="child.type === 'section'"
          :node="child"
          :schema="schema"
          :model-value="modelValue"
          :readonly="readonly"
          :errors="errors"
          @update:field="(prop, val) => emit('update:field', prop, val)"
        />

        <FormRow
          v-else-if="child.type === 'row'"
          :node="child"
          :schema="schema"
          :model-value="modelValue"
          :readonly="readonly"
          :errors="errors"
          @update:field="(prop, val) => emit('update:field', prop, val)"
        />

        <FormField
          v-else-if="child.type === 'field'"
          :node="child"
          :schema="schema"
          :model-value="modelValue"
          :readonly="readonly"
          :errors="errors"
          @update:field="(prop, val) => emit('update:field', prop, val)"
        />

        <component
          v-else-if="child.type === 'display' && getDisplayComponent((child as any).control)"
          :is="getDisplayComponent((child as any).control)"
          :node="child"
        />
      </template>
    </div>
  </fieldset>
</template>

<style scoped>
.bosca-form-section {
  margin: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.section-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg-1);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  padding: 0 4px;
}

.section-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
</style>
