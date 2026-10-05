<script setup lang="ts">
import { computed } from 'vue'
import {
  containerDefaultTemplate,
  type ItemTemplate,
  type PreviewChild,
} from './library-utils'

// Vertical list of rich rows — each item is a "detail-row" by default with
// thumbnail + multi-line text + badges + progress. Density is configurable;
// dividers are an option. This is the container that gives editors a real
// answer to "how do I show rich list items".

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

const showDividers = computed(() => props.block.uiConfig.showDividers !== false)

const density = computed<'compact' | 'comfortable' | 'spacious'>(() => {
  const d = props.block.uiConfig.density
  if (d === 'compact' || d === 'spacious') return d
  return 'comfortable'
})

const gap = computed(() => {
  switch (density.value) {
    case 'compact':   return '4px'
    case 'spacious':  return '14px'
    default:          return '8px'
  }
})

const template = computed<ItemTemplate>(() =>
  props.block.itemTemplate ?? containerDefaultTemplate('detail-list'),
)

const isEmpty = computed(() => props.block.children.length === 0)
</script>

<template>
  <div v-if="isEmpty" class="detail-list-empty">
    <span>No items</span>
  </div>
  <div
    v-else
    class="detail-list"
    :class="{ 'detail-list--dividers': showDividers }"
    :style="{ gap }"
  >
    <LibraryItemPreview
      v-for="item in block.children"
      :key="item.id"
      :item="item"
      :template="item.bindingOverride ?? template"
    />
  </div>
</template>

<style scoped>
.detail-list { display: flex; flex-direction: column; }
.detail-list--dividers > :not(:last-child) {
  border-bottom: 1px solid #f0ece7;
}
.detail-list-empty {
  padding: 24px;
  text-align: center;
  border: 1px dashed color-mix(in oklch, var(--fg-3, #8b8b8b) 30%, transparent);
  border-radius: 10px;
  font-size: 12px;
  color: var(--fg-3, #8b8b8b);
}
</style>
