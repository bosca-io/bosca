<script lang="ts" setup>
import type { CollectionTemplateFilterInput } from '~/types/graphql'

defineProps<{
  onDelete: () => void
}>()

const filter = defineModel<CollectionTemplateFilterInput>('filter', { required: true })
</script>

<template>
  <div class="filter-editor">
    <div class="filter-grid">
      <TextInput v-model="filter.name" label="Name" placeholder="Filter name" />
      <TextInput
        v-model="filter.filter"
        label="Filter Expression"
        mono
        placeholder="traitIds = blog-post" />
    </div>
    <div class="delete-row">
      <Button size="sm" icon="trash" @click="onDelete">Delete</Button>
    </div>
  </div>
</template>

<style scoped>
.filter-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-top: 1px solid var(--line);
  background: color-mix(in oklch, var(--bg-2) 50%, transparent);
}

.filter-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.delete-row {
  display: flex;
  justify-content: flex-end;
}
</style>
