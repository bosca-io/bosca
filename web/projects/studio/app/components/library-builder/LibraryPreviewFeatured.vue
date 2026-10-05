<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  block: { name: string; uiConfig: Record<string, unknown>; featuredImageUrl: string | null }
  device: string
}>()

const height = computed(() => {
  const h = props.block.uiConfig.height
  if (h === 'small') return '80px'
  if (h === 'medium') return '120px'
  return '160px'
})

// The editor exposes textAlignment AND overlay; both shape the preview. If we
// silently ignored them, editors would change Bottom-Right and see nothing,
// then assume the config does nothing in the client app either.
const textAlignment = computed(() => {
  const v = props.block.uiConfig.textAlignment
  return typeof v === 'string' ? v : 'center'
})

// The `.featured` container is `display: flex` with default row direction:
//   justify-content = main axis = HORIZONTAL placement
//   align-items     = cross axis = VERTICAL placement
// Map each option to (horizontal, vertical) — using named keys so the binding
// can't accidentally swap them again.
const textPlacement = computed<{ horizontal: string; vertical: string }>(() => {
  switch (textAlignment.value) {
    case 'left':         return { horizontal: 'flex-start', vertical: 'center' }
    case 'right':        return { horizontal: 'flex-end',   vertical: 'center' }
    case 'bottom':       return { horizontal: 'center',     vertical: 'flex-end' }
    case 'bottom-left':  return { horizontal: 'flex-start', vertical: 'flex-end' }
    case 'bottom-right': return { horizontal: 'flex-end',   vertical: 'flex-end' }
    default:             return { horizontal: 'center',     vertical: 'center' }
  }
})

const textAlignCss = computed(() => {
  switch (textAlignment.value) {
    case 'left':
    case 'bottom-left': return 'left'
    case 'right':
    case 'bottom-right': return 'right'
    default: return 'center'
  }
})

const overlay = computed(() => {
  const v = props.block.uiConfig.overlay
  return typeof v === 'string' ? v : 'dark'
})

const overlayBackground = computed(() => {
  switch (overlay.value) {
    case 'none':  return 'transparent'
    case 'light': return 'linear-gradient(to bottom, transparent 30%, rgba(255,255,255,0.45))'
    default:      return 'linear-gradient(to bottom, transparent 30%, rgba(0,0,0,0.4))'
  }
})

const textColor = computed(() => (overlay.value === 'light' ? '#1a1a1a' : 'white'))
</script>

<template>
  <div
    class="featured"
    :style="{
      height,
      justifyContent: textPlacement.horizontal,
      alignItems: textPlacement.vertical,
    }"
  >
    <img
      v-if="block.featuredImageUrl"
      :src="block.featuredImageUrl"
      class="featured-img"
      alt=""
    >
    <div class="featured-overlay" :style="{ background: overlayBackground }" />
    <span
      class="featured-text"
      :style="{ color: textColor, textAlign: textAlignCss }"
    >{{ block.name }}</span>
  </div>
</template>

<style scoped>
.featured {
  position: relative; border-radius: 14px; overflow: hidden;
  display: flex;
  background: linear-gradient(135deg, #6b8f71, #4a6e50);
}
.featured-img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.featured-overlay { position: absolute; inset: 0; }
.featured-text {
  position: relative; z-index: 1; font-family: serif;
  font-size: 28px; font-weight: 700; padding: 12px; line-height: 1.1;
}
</style>
