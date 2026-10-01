<script setup lang="ts">
import Button from './Button.vue'

const props = defineProps<{
  page: number
  totalPages: number
}>()

const emit = defineEmits<{
  prev: []
  next: []
}>()
</script>

<template>
  <div class="pagination">
    <Button size="sm" :disabled="page <= 1" @click="emit('prev')">Prev</Button>
    <span class="mono pagination-label">{{ page }} / {{ totalPages.toLocaleString() }}</span>
    <Button size="sm" :disabled="page >= totalPages" @click="emit('next')">Next</Button>
  </div>
</template>

<style scoped>
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 14px;
  border-top: 1px solid color-mix(in oklch, var(--line) 37%, transparent);
}

.pagination-label {
  font-size: 12px;
  color: var(--fg-2);
}
</style>
