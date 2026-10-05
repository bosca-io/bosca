<script setup lang="ts">
import { computed } from 'vue'
import {
  containerDefaultTemplate,
  type ItemTemplate,
  type PreviewChild,
} from './library-utils'

// A vertical stack of full-bleed cards — Instagram-style feed. Each item
// renders via LibraryItemPreview using the resolved template. Spacing and
// edge style are controlled by the container's uiConfig; per-item shape by
// the ItemTemplate.

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

const spacing = computed(() => {
  const s = props.block.uiConfig.spacing
  if (s === 'tight') return '4px'
  if (s === 'loose') return '20px'
  return '12px'
})

const fullBleed = computed(() => props.block.uiConfig.fullBleed !== false)

const template = computed<ItemTemplate>(() =>
  props.block.itemTemplate ?? containerDefaultTemplate('stack'),
)

const isEmpty = computed(() => props.block.children.length === 0)
</script>

<template>
  <div class="stack-block" :class="{ 'stack-block--bleed': fullBleed }">
    <div v-if="isEmpty" class="stack-empty">
      <span>No items</span>
    </div>
    <div v-else class="stack-list" :style="{ gap: spacing }">
      <LibraryItemPreview
        v-for="item in block.children"
        :key="item.id"
        :item="item"
        :template="item.bindingOverride ?? template"
      />
    </div>
  </div>
</template>

<style scoped>
.stack-block { display: flex; flex-direction: column; }
.stack-block--bleed { margin-left: -14px; margin-right: -14px; }
.stack-list { display: flex; flex-direction: column; }
.stack-empty {
  padding: 24px;
  text-align: center;
  border: 1px dashed color-mix(in oklch, var(--fg-3, #8b8b8b) 30%, transparent);
  border-radius: 10px;
  font-size: 12px;
  color: var(--fg-3, #8b8b8b);
}
</style>
