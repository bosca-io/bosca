<script lang="ts" setup>
import { AttributeLocation, AttributeType, Order, type Ordering } from '~/types/graphql'

defineProps<{
  onDelete: () => void
}>()

const ordering = defineModel<Ordering>('ordering', { required: true })

const typeOptions = Object.values(AttributeType).map(t => ({ value: t, label: t }))
const locationOptions = Object.values(AttributeLocation).map(t => ({ value: t, label: t }))
const orderOptions = Object.values(Order).map(t => ({ value: t, label: t }))

const pathString = ref((ordering.value.path || []).join(', '))

watch(pathString, (val) => {
  ordering.value.path = val.split(',').map(s => s.trim()).filter(Boolean)
})
</script>

<template>
  <div class="ordering-editor">
    <div class="ordering-grid">
      <TextInput
        :model-value="ordering.field ?? ''"
        label="Field"
        placeholder="field_name"
        @update:model-value="ordering.field = $event || null" />
      <Select v-model="ordering.order" :options="orderOptions" label="Order" />
      <Select v-model="ordering.type" :options="typeOptions" label="Type" />
      <Select v-model="ordering.location" :options="locationOptions" label="Location" />
    </div>
    <TextInput
      v-model="pathString"
      label="Path (comma-separated)"
      mono
      placeholder="attributes, nested, key" />
    <div class="delete-row">
      <Button size="sm" icon="trash" @click="onDelete">Delete</Button>
    </div>
  </div>
</template>

<style scoped>
.ordering-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-top: 1px solid var(--line);
  background: color-mix(in oklch, var(--bg-2) 50%, transparent);
}

.ordering-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.delete-row {
  display: flex;
  justify-content: flex-end;
}
</style>
