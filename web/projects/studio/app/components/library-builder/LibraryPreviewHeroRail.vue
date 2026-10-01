<script setup lang="ts">
import { computed } from 'vue'
import {
  VARIANT_DEFAULTS,
  cloneTemplate,
  containerDefaultTemplate,
  type ItemTemplate,
  type PreviewChild,
} from './library-utils'

// Hero + Rail — the first child renders as a large hero card; the rest as a
// horizontal rail beneath. Container config picks rail width; the ItemTemplate
// drives the rail item appearance. The hero is rendered as a hero-card,
// independent of the template variant — it's the container's distinctive
// visual contract.

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

const railItemWidth = computed(() => {
  const w = props.block.uiConfig.railItemWidth
  if (w === 'small') return '80px'
  if (w === 'large') return '140px'
  return '110px'
})

const showRailTitle = computed(() => props.block.uiConfig.showRailTitle !== false)

const railTemplate = computed<ItemTemplate>(() =>
  props.block.itemTemplate ?? containerDefaultTemplate('hero-rail'),
)

const heroItem = computed(() => props.block.children[0] ?? null)
const railItems = computed(() => props.block.children.slice(1))

// The hero is the contract of this container — it's always a hero-card. We
// preserve the user's image/title/subtitle/progress *settings* (from the hero
// item's own override when set, else the shared template), but force the
// variant + image placement so the hero looks like a hero regardless of how
// the rail items are styled.
const heroTemplate = computed<ItemTemplate>(() => {
  const heroBase = cloneTemplate(VARIANT_DEFAULTS['hero-card'])
  const rail = heroItem.value?.bindingOverride ?? railTemplate.value
  return {
    ...heroBase,
    title: { ...rail.title, placement: 'overlay', show: rail.title.show },
    subtitle: { ...rail.subtitle },
    badges: { ...rail.badges },
    progress: { ...rail.progress },
    action: { ...rail.action },
  }
})

const isEmpty = computed(() => props.block.children.length === 0)
</script>

<template>
  <div class="hero-rail-block">
    <div v-if="isEmpty" class="hero-rail-empty">
      <span>No items</span>
    </div>
    <template v-else>
      <div v-if="heroItem" class="hero-rail-hero">
        <LibraryItemPreview :item="heroItem" :template="heroTemplate" />
      </div>
      <div v-if="showRailTitle && block.name" class="hero-rail-title">{{ block.name }}</div>
      <div v-if="railItems.length > 0" class="hero-rail-track">
        <div
          v-for="item in railItems"
          :key="item.id"
          class="hero-rail-cell"
          :style="{ width: railItemWidth, minWidth: railItemWidth }"
        >
          <LibraryItemPreview :item="item" :template="item.bindingOverride ?? railTemplate" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.hero-rail-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
}
.hero-rail-hero {
  margin-bottom: 4px;
}
.hero-rail-title {
  font-size: 13px;
  font-weight: 600;
  color: #2c2c2c;
}
.hero-rail-track {
  display: flex;
  gap: 8px;
  overflow: hidden;
}
.hero-rail-cell {
  flex-shrink: 0;
}
.hero-rail-empty {
  padding: 24px;
  text-align: center;
  border: 1px dashed color-mix(in oklch, var(--fg-3, #8b8b8b) 30%, transparent);
  border-radius: 10px;
  font-size: 12px;
  color: var(--fg-3, #8b8b8b);
}
</style>
