<script setup lang="ts">
import { computed, provide } from 'vue'
import type { ItemTemplate, PreviewChild } from './library-utils'

interface PreviewBlock {
  id: string | null
  name: string
  uiType: string
  uiConfig: Record<string, unknown>
  featuredImageUrl: string | null
  children: PreviewChild[]
  // Effective ItemTemplate for the block's children — resolved by the page
  // owner so every container preview can render items consistently via
  // LibraryItemPreview. Optional because non-container blocks (banner,
  // featured, status-row, section-header) don't render children.
  itemTemplate?: ItemTemplate
}

const props = defineProps<{
  blocks: PreviewBlock[]
  device: 'mobile' | 'tablet' | 'desktop'
  accent: string
  // The title shown at the top of the device frame — generally the current
  // view's name. Falls back to a sensible default so the preview isn't empty
  // while loading.
  title: string
  // Optional click handler — when provided, items inside this preview become
  // clickable and call back with item identity + the resolved template.
  // Surfaces inside the editor (canvas) deliberately don't pass this; only
  // the floating device preview does, so item clicks there describe the
  // configured action instead of stealing the canvas's selection click.
  onItemClick?: (info: { itemId: string | null; itemName: string; isCollection: boolean; template: ItemTemplate }) => void
  // True when the preview has been navigated into a sub-collection (the
  // user clicked an item) and a "Back" affordance should appear in the
  // device's status bar.
  canGoBack?: boolean
  onBack?: () => void
  // True while a navigation fetch is in flight; the device viewport
  // dims and a spinner appears so the user gets immediate feedback that
  // their click was received.
  loading?: boolean
}>()

// Provide the click handler down to LibraryItemPreview via injection. When
// the prop is undefined the handler is null and previews stay purely
// presentational (no cursor, no hover lift). We can't conditionally provide
// — Vue's provide must be called every render — so we always provide and
// let the injection consumer treat null as "no interactivity".
provide('libraryPreviewItemClick', props.onItemClick ?? null)

function back() {
  props.onBack?.()
}

const deviceConfig = computed(() => {
  switch (props.device) {
    case 'tablet': return { width: 768, height: 1024, radius: 20 }
    case 'desktop': return { width: 1280, height: 800, radius: 8 }
    default: return { width: 375, height: 812, radius: 44 }
  }
})
</script>

<template>
  <div class="preview-container">
    <div
      class="device-frame"
      :class="device"
      :style="{
        '--device-radius': deviceConfig.radius + 'px',
        aspectRatio: deviceConfig.width + ' / ' + deviceConfig.height,
      }"
    >
      <div v-if="device === 'mobile'" class="device-notch" />
      <div v-if="device === 'desktop'" class="browser-bar">
        <div class="traffic-lights">
          <span class="dot red" /><span class="dot yellow" /><span class="dot green" />
        </div>
        <span class="url-text">app.example.com/library</span>
      </div>

      <div class="device-viewport" :class="{ 'device-viewport--loading': loading }">
        <div class="preview-header">
          <button
            v-if="canGoBack"
            class="preview-back"
            type="button"
            aria-label="Back"
            @click="back"
          >‹ Back</button>
          <div class="preview-title">{{ title || 'Content Library' }}</div>
          <span v-if="canGoBack" class="preview-back-spacer" />
        </div>
        <div v-if="loading" class="preview-loading-overlay">
          <div class="preview-loading-spinner" />
        </div>
        <div class="preview-content">
          <template v-for="(block, idx) in blocks" :key="block.id ?? `new-${idx}`">
            <LibraryPreviewStatusRow v-if="block.uiType === 'status-row'" :block="block" :device="device" />
            <LibraryPreviewFeatured v-else-if="block.uiType === 'featured'" :block="block" :device="device" />
            <LibraryPreviewGrid v-else-if="block.uiType === 'grid'" :block="block" :device="device" />
            <LibraryPreviewCarousel v-else-if="block.uiType === 'carousel'" :block="block" :device="device" />
            <LibraryPreviewBanner v-else-if="block.uiType === 'banner'" :block="block" :device="device" />
            <LibraryPreviewSectionHeader v-else-if="block.uiType === 'section-header'" :block="block" :device="device" />
            <LibraryPreviewCompactList v-else-if="block.uiType === 'compact-list'" :block="block" :device="device" />
            <LibraryPreviewDetailList v-else-if="block.uiType === 'detail-list'" :block="block" :device="device" />
            <LibraryPreviewStack v-else-if="block.uiType === 'stack'" :block="block" :device="device" />
            <LibraryPreviewMasonry v-else-if="block.uiType === 'masonry'" :block="block" :device="device" />
            <LibraryPreviewHeroRail v-else-if="block.uiType === 'hero-rail'" :block="block" :device="device" />
            <div v-else class="preview-unknown">
              <span class="preview-unknown-type">{{ block.uiType || 'no type' }}</span>
              <span class="preview-unknown-name">{{ block.name }}</span>
            </div>
          </template>
          <div v-if="blocks.length === 0" class="preview-empty">
            <span>Add blocks to see a preview</span>
          </div>
        </div>
      </div>

      <div v-if="device === 'mobile'" class="device-home-bar" />
    </div>
  </div>
</template>

<style scoped>
.preview-container {
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 16px;
  height: 100%;
  overflow: auto;
}

.device-frame {
  width: 100%;
  max-width: 100%;
  background: #faf8f5;
  border: 2px solid color-mix(in oklch, var(--fg-3) 30%, transparent);
  border-radius: var(--device-radius);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.device-notch {
  width: 100px;
  height: 24px;
  background: color-mix(in oklch, var(--fg-3) 40%, transparent);
  border-radius: 0 0 14px 14px;
  margin: 0 auto;
  flex-shrink: 0;
}

.browser-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  background: #2a2a2a;
  border-bottom: 1px solid #444;
  flex-shrink: 0;
}

.traffic-lights { display: flex; gap: 5px; }
.dot { width: 8px; height: 8px; border-radius: 50%; }
.dot.red { background: #ff5f57; }
.dot.yellow { background: #febc2e; }
.dot.green { background: #28c840; }
.url-text {
  font-size: 10px;
  color: #999;
  background: #1a1a1a;
  padding: 2px 8px;
  border-radius: 4px;
  flex: 1;
  text-align: center;
}

.device-viewport {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  position: relative;
}
.device-viewport--loading > .preview-content,
.device-viewport--loading > .preview-header {
  opacity: 0.4;
  pointer-events: none;
  transition: opacity 0.15s;
}

.preview-loading-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.preview-loading-spinner {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 2px solid rgba(0, 0, 0, 0.1);
  border-top-color: #5a7d60;
  animation: preview-spin 0.8s linear infinite;
}
@keyframes preview-spin {
  to { transform: rotate(360deg); }
}

.device-home-bar {
  width: 80px;
  height: 4px;
  background: #ccc;
  border-radius: 2px;
  margin: 6px auto;
  flex-shrink: 0;
}

.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 14px;
}
.preview-title {
  flex: 1;
  text-align: center;
  font-size: 20px;
  font-weight: 700;
  color: #2c2c2c;
  font-family: serif;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-back {
  all: unset;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: #5a7d60;
  padding: 4px 8px;
  border-radius: 6px;
  flex-shrink: 0;
}
.preview-back:hover { background: rgba(0, 0, 0, 0.05); }
/* Spacer matches the back button's footprint so the title stays centered. */
.preview-back-spacer {
  display: inline-block;
  width: 52px;
  flex-shrink: 0;
}
.preview-content { display: flex; flex-direction: column; gap: 8px; }
.preview-empty {
  padding: 32px;
  text-align: center;
  color: #aaa;
  font-size: 12px;
}

.preview-unknown {
  padding: 10px 12px;
  border: 1px dashed #ccc;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview-unknown-type {
  font-size: 10px;
  text-transform: uppercase;
  color: #999;
  background: #eee;
  padding: 2px 6px;
  border-radius: 4px;
}
.preview-unknown-name { font-size: 12px; color: #666; }
</style>
