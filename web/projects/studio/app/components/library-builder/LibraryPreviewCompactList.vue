<script setup lang="ts">
import { computed } from 'vue'
import {
  containerDefaultTemplate,
  type ItemTemplate,
  type PreviewChild,
} from './library-utils'

// Compact vertical list of items, one per row. Per-item rendering is
// fully delegated to LibraryItemPreview via the ItemTemplate — image
// placement is a template concern, not a container concern. The user
// controls "do I show a thumbnail" by setting template.image.placement
// in the Item Presentation panel; we don't second-guess them here.

const props = defineProps<{
  block: {
    name: string
    uiConfig: Record<string, unknown>
    featuredImageUrl: string | null
    children: PreviewChild[]
    itemTemplate?: ItemTemplate
  }
  device: string
}>()

const template = computed<ItemTemplate>(() =>
  props.block.itemTemplate ?? containerDefaultTemplate('compact-list'),
)

const isEmpty = computed(() => props.block.children.length === 0)
</script>

<template>
  <div v-if="isEmpty" class="compact-empty">
    <span>No items</span>
  </div>
  <div v-else class="compact-list">
    <LibraryItemPreview
      v-for="item in block.children"
      :key="item.id"
      :item="item"
      :template="item.bindingOverride ?? template"
    />
  </div>
</template>

<style scoped>
.compact-list { display: flex; flex-direction: column; }
.compact-empty {
  padding: 24px 8px;
  text-align: center;
  border: 1px dashed color-mix(in oklch, var(--fg-3, #8b8b8b) 30%, transparent);
  border-radius: 10px;
  font-size: 12px;
  color: var(--fg-3, #8b8b8b);
}
</style>
