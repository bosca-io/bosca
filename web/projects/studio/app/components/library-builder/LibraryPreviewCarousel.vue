<script setup lang="ts">
import { computed } from 'vue'
import {
  containerDefaultTemplate,
  type ItemTemplate,
  type PreviewChild,
} from './library-utils'

// Horizontal scrolling row of cards. Container picks card width; per-item
// rendering is delegated to LibraryItemPreview using the effective
// ItemTemplate (so a Carousel can show tiles or cards depending on the
// template the child collection or binding sets).

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

const itemWidth = computed(() => {
  const w = props.block.uiConfig.itemWidth as string
  if (w === 'small') return '80px'
  if (w === 'large') return '140px'
  return '110px'
})

const template = computed<ItemTemplate>(() =>
  props.block.itemTemplate ?? containerDefaultTemplate('carousel'),
)

const isEmpty = computed(() => props.block.children.length === 0)
</script>

<template>
  <div class="carousel-block">
    <div v-if="block.uiConfig.showTitle !== false" class="carousel-title">{{ block.name }}</div>
    <div v-if="isEmpty" class="carousel-empty">
      <span>No items</span>
    </div>
    <div v-else class="carousel-track">
      <div
        v-for="item in block.children"
        :key="item.id"
        class="carousel-cell"
        :style="{ width: itemWidth, minWidth: itemWidth }"
      >
        <LibraryItemPreview :item="item" :template="item.bindingOverride ?? template" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.carousel-block { overflow: hidden; }
.carousel-title { font-size: 13px; font-weight: 600; color: #2c2c2c; margin-bottom: 8px; }
.carousel-track { display: flex; gap: 8px; overflow: hidden; }
.carousel-cell { flex-shrink: 0; }
.carousel-empty {
  height: 110px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed color-mix(in oklch, var(--fg-3, #8b8b8b) 30%, transparent);
  border-radius: 10px;
  font-size: 12px;
  color: var(--fg-3, #8b8b8b);
}
</style>
