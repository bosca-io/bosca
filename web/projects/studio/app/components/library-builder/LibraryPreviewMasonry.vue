<script setup lang="ts">
import { computed } from 'vue'
import {
  containerDefaultTemplate,
  type ItemTemplate,
  type PreviewChild,
} from './library-utils'

// Pinterest-style masonry — N columns, items packed by CSS columns so they
// can have varying heights without coordinating. The container picks columns
// + gap; per-item shape comes from the ItemTemplate (variant decides whether
// items are tiles or cards).

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

const columns = computed(() => {
  const c = props.block.uiConfig.columns
  if (typeof c === 'number' && c >= 1 && c <= 4) return c
  return 2
})

const gap = computed(() => {
  const g = props.block.uiConfig.gap
  if (g === 'tight') return '4px'
  if (g === 'loose') return '14px'
  return '8px'
})

const template = computed<ItemTemplate>(() =>
  props.block.itemTemplate ?? containerDefaultTemplate('masonry'),
)

const isEmpty = computed(() => props.block.children.length === 0)
</script>

<template>
  <div v-if="isEmpty" class="masonry-empty">
    <span>No items</span>
  </div>
  <div
    v-else
    class="masonry"
    :style="{ columnCount: columns, columnGap: gap }"
  >
    <div
      v-for="item in block.children"
      :key="item.id"
      class="masonry-cell"
      :style="{ marginBottom: gap }"
    >
      <LibraryItemPreview :item="item" :template="item.bindingOverride ?? template" />
    </div>
  </div>
</template>

<style scoped>
.masonry-empty {
  padding: 24px;
  text-align: center;
  border: 1px dashed color-mix(in oklch, var(--fg-3, #8b8b8b) 30%, transparent);
  border-radius: 10px;
  font-size: 12px;
  color: var(--fg-3, #8b8b8b);
}
.masonry-cell {
  break-inside: avoid;
  -webkit-column-break-inside: avoid;
}
</style>
