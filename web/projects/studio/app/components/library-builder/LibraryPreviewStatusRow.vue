<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  block: { name: string; uiConfig: Record<string, unknown>; featuredImageUrl: string | null }
  device: string
}>()

// Map each editor-supported icon value to its preview glyph. Must mirror the
// `iconOptions` list in LibraryBlockEditor exactly — adding to one without
// the other produces a silently-wrong preview.
const ICON_GLYPHS: Record<string, string> = {
  'book-open': '📖',
  'bookmark': '🔖',
  'clock': '🕐',
}

const icon = computed(() => {
  const v = props.block.uiConfig.icon
  if (typeof v !== 'string') return ICON_GLYPHS['book-open']
  return ICON_GLYPHS[v] ?? ICON_GLYPHS['book-open']
})

// The empty-message string the user configures shows beneath the row label
// as a placeholder secondary line — so editors can see what users will see
// when the data source returns zero items.
const emptyMessage = computed(() => {
  const v = props.block.uiConfig.emptyMessage
  return typeof v === 'string' && v.trim().length > 0 ? v : 'No items yet'
})
</script>

<template>
  <div class="status-row">
    <span class="status-icon">{{ icon }}</span>
    <div class="status-body">
      <span class="status-label">{{ block.name }}</span>
      <span class="status-empty">{{ emptyMessage }}</span>
    </div>
    <!--
      The real client render pulls a live count from profile APIs (progress/
      marks/bookmarks). We can't know that count from the editor — show an
      em-dash to signal "data populated at runtime" rather than the
      misleading "0".
    -->
    <span class="status-badge">—</span>
  </div>
</template>

<style scoped>
.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid #e8e4df;
}

.status-icon { font-size: 16px; flex-shrink: 0; }

.status-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }

.status-label {
  font-size: 14px;
  font-weight: 500;
  color: #2c2c2c;
}

.status-empty {
  font-size: 11px;
  color: #777;
  font-style: italic;
}

.status-badge {
  font-size: 12px;
  font-weight: 600;
  color: #2c2c2c;
  background: white;
  padding: 2px 10px;
  border-radius: 6px;
  flex-shrink: 0;
}
</style>
