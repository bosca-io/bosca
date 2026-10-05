<script setup lang="ts">
interface ParentCollection {
  id: string
  name: string
  workflow?: { state?: string | null } | null
}

defineProps<{
  collections: ParentCollection[]
  accent: string
}>()

defineEmits<{
  remove: [collectionId: string]
}>()
</script>

<template>
  <div class="parent-collection-list">
    <div v-for="collection in collections" :key="collection.id" class="parent-collection-row">
      <NuxtLink
        :to="`/cms/collections/${collection.id}`"
        class="parent-collection-link"
        :aria-label="`Open collection ${collection.name}`">
        <Icon name="folder" :size="14" :color="accent" />
        <span class="parent-collection-text">
          <span class="parent-collection-name">{{ collection.name }}</span>
          <span class="mono parent-collection-id">{{ collection.id }}</span>
        </span>
        <Badge
          v-if="collection.workflow?.state"
          :color="collection.workflow.state === 'published' ? '#34d99a' : '#6c7388'">
          {{ collection.workflow.state }}
        </Badge>
      </NuxtLink>
      <button
        class="parent-collection-remove"
        :aria-label="`Remove ${collection.name} from parent collections`"
        @click="$emit('remove', collection.id)">
        <Icon name="x" :size="12" color="var(--fg-3)" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.parent-collection-list { display: flex; flex-direction: column; }

.parent-collection-row {
  display: flex; align-items: center; gap: 10px; padding: 8px 6px;
  border-radius: var(--r-xs); transition: background 0.12s;
}

.parent-collection-row:hover { background: color-mix(in oklch, var(--brand-2) 5%, transparent); }

.parent-collection-link {
  display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1;
  color: inherit; text-decoration: none;
}

.parent-collection-link:focus-visible { outline: 2px solid v-bind(accent); outline-offset: 3px; border-radius: var(--r-xs); }
.parent-collection-link:hover .parent-collection-name { color: var(--brand-2); }
.parent-collection-text { display: flex; flex: 1; min-width: 0; flex-direction: column; }

.parent-collection-name {
  overflow: hidden; color: var(--fg-0); font-size: 13px; font-weight: 500;
  text-overflow: ellipsis; white-space: nowrap;
}

.parent-collection-id {
  overflow: hidden; margin-top: 1px; color: var(--fg-4); font-size: 11px;
  text-overflow: ellipsis; white-space: nowrap;
}

.parent-collection-remove {
  display: flex; width: 24px; height: 24px; flex-shrink: 0; align-items: center;
  justify-content: center; border: none; border-radius: var(--r-xs); background: none;
  opacity: 0; cursor: pointer;
  transition: opacity 0.12s, background 0.12s;
}

.parent-collection-row:hover .parent-collection-remove,
.parent-collection-remove:focus-visible { opacity: 1; }
.parent-collection-remove:hover { background: var(--bg-3); }
</style>
