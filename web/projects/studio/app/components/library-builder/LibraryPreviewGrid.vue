<script setup lang="ts">
import { computed } from 'vue'
import {
  aspectRatioNumber,
  containerDefaultTemplate,
  type ItemTemplate,
  type PreviewChild,
} from './library-utils'

// Grid container — N columns of items. Only `columns` is a Grid-level
// concern; per-item appearance (including aspect ratio) is decided by the
// ItemTemplate. Earlier this component overrode the template's aspect with
// the Grid's own uiConfig.aspectRatio, but that broke the Item Presentation
// contract: the user changes Aspect in the panel and expects every render
// to follow. Template wins.

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

const columns = computed(() => (props.block.uiConfig.columns as number) ?? 2)
const showMoreLink = computed(() => props.block.uiConfig.showMoreLink === true)

const template = computed<ItemTemplate>(() =>
  props.block.itemTemplate ?? containerDefaultTemplate('grid'),
)

const isEmpty = computed(() => props.block.children.length === 0)
const aspect = computed(() => aspectRatioNumber(template.value.image.aspect))
</script>

<template>
  <div
    v-if="isEmpty"
    class="grid-empty"
    :style="{ gridTemplateColumns: `repeat(${columns}, 1fr)` }"
  >
    <div
      v-for="i in columns"
      :key="i"
      class="grid-tile grid-tile--empty"
      :style="{ aspectRatio: aspect }"
    />
    <span class="empty-text">No items</span>
  </div>
  <div v-else class="grid-block">
    <div
      class="grid"
      :style="{ gridTemplateColumns: `repeat(${columns}, 1fr)` }"
    >
      <LibraryItemPreview
        v-for="item in block.children"
        :key="item.id"
        :item="item"
        :template="item.bindingOverride ?? template"
      />
    </div>
    <div v-if="showMoreLink" class="grid-more">See all →</div>
  </div>
</template>

<style scoped>
.grid-block { display: flex; flex-direction: column; gap: 8px; }
.grid { display: grid; gap: 8px; }
.grid-more {
  align-self: flex-end;
  font-size: 12px;
  font-weight: 600;
  color: #5a7d60;
  padding: 2px 0;
}

.grid-empty {
  position: relative;
  display: grid;
  gap: 8px;
}
.grid-tile--empty {
  background: repeating-linear-gradient(
    45deg,
    color-mix(in oklch, var(--fg-3, #8b8b8b) 6%, transparent),
    color-mix(in oklch, var(--fg-3, #8b8b8b) 6%, transparent) 8px,
    transparent 8px,
    transparent 16px
  );
  border: 1px dashed color-mix(in oklch, var(--fg-3, #8b8b8b) 30%, transparent);
  border-radius: 12px;
}
.empty-text {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--fg-3, #8b8b8b);
  pointer-events: none;
}
</style>
