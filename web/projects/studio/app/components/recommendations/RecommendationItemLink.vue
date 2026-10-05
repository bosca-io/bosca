<script setup lang="ts">
const props = defineProps<{
  metadata?: { id: string; name: string; attributes?: unknown } | null
  collection?: { id: string; name: string; attributes?: unknown } | null
}>()

const item = computed(() => props.metadata ?? props.collection)
const kind = computed(() => props.metadata ? 'Metadata' : 'Collection')
const link = computed(() => item.value
  ? `/cms/${props.metadata ? 'metadata' : 'collections'}/${encodeURIComponent(item.value.id)}`
  : '')
const editorialType = computed(() => {
  const attributes = item.value?.attributes
  if (!attributes || typeof attributes !== 'object' || Array.isArray(attributes)) return null
  const type = (attributes as Record<string, unknown>).type
  return typeof type === 'string' && type.trim() ? type.trim() : null
})
</script>

<template>
  <NuxtLink v-if="item" :to="link" class="recommendation-item-link">
    <span class="item-name">{{ item.name }}</span>
    <span class="item-type">{{ kind }} · {{ editorialType ?? 'No editorial type' }}</span>
  </NuxtLink>
  <span v-else>Unavailable item</span>
</template>

<style scoped>
.recommendation-item-link { display: flex; flex-direction: column; min-width: 0; gap: 3px; text-decoration: none; }
.item-name { color: var(--fg-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-type { color: var(--fg-3); font-size: 11.5px; }
.recommendation-item-link:hover .item-name { text-decoration: underline; }
</style>
