<script setup lang="ts">
import { computed, inject } from 'vue'
import { aspectRatioNumber, type ItemTemplate } from './library-utils'

// When this preview is rendered inside an interactive surface (the floating
// device preview), an ancestor injects a click handler so the user can poke
// the items and see what their template would do. When not provided (e.g.
// inside the canvas, where clicks are for selection), the preview is just
// presentational.
const onItemClick = inject<((info: {
  itemId: string | null
  itemName: string
  isCollection: boolean
  template: ItemTemplate
  ownUiType?: string
  bindingOverride?: ItemTemplate | null
}) => void) | null>('libraryPreviewItemClick', null)

// A single child item being rendered inside a container, shaped by an
// ItemTemplate. One component dispatches to one of five variant layouts so
// the canvas and every container preview render items identically — change
// here once, every surface picks it up.

interface ItemData {
  id: string | null
  name: string
  imageUrl: string | null
  // Indicates whether this item represents a sub-collection (drillable in
  // the preview's app simulator) vs. a leaf content item. The interactive
  // preview reads this on click to decide navigate-into vs. describe-only.
  // Optional with default false so callers that don't track it don't break.
  isCollection?: boolean
  // The item's own block uiType + parent→item binding override, forwarded in
  // the click payload so the interactive preview can navigate INTO this item
  // and resolve its children's template consistently with the editor.
  uiType?: string
  bindingOverride?: ItemTemplate | null
  // Optional content fields the template may surface. Today the loader only
  // fills `name`; once we extend the fetch to include subtitle/description/
  // category/duration/progress these slots populate automatically.
  subtitle?: string | null
  description?: string | null
  category?: string | null
  duration?: string | null
  progress?: number | null
}

const props = defineProps<{
  item: ItemData
  template: ItemTemplate
  // Optional: lays a subtle highlight when the user has selected this item.
  selected?: boolean
}>()

const aspect = computed(() => aspectRatioNumber(props.template.image.aspect))

// In the editor preview we always render slot CONTENT for the user — when
// the loaded item lacks real data for the configured source (subtitle,
// category, duration, progress), we substitute a short placeholder. That
// way toggling "Show subtitle" actually shows something in the preview,
// even before subtitle/description/etc. fields are populated upstream. In
// real client rendering these fields will be present; this fallback only
// affects the studio preview surface.
const subtitleText = computed<string | null>(() => {
  if (!props.template.subtitle.show) return null
  switch (props.template.subtitle.source) {
    case 'subtitle':    return props.item.subtitle ?? 'Subtitle text'
    case 'description': return props.item.description ?? 'Description text'
    case 'category':    return props.item.category ?? 'Category'
    default:            return null
  }
})

const badgeText = computed<string | null>(() => {
  if (!props.template.badges.show) return null
  switch (props.template.badges.source) {
    case 'category': return props.item.category ?? 'Category'
    case 'duration': return props.item.duration ?? '12 min'
    case 'status':   return 'Status'
    default:         return null
  }
})

const progressPct = computed<number | null>(() => {
  if (!props.template.progress.show) return null
  if (props.template.progress.source === 'none') return null
  const v = props.item.progress
  if (typeof v !== 'number' || !Number.isFinite(v)) return 65
  return Math.max(0, Math.min(100, v))
})

const titleLines = computed(() => props.template.title.lines)

// Render an action label only when the user has explicitly turned it on
// (action.show=true). Falls back to a kind-appropriate string ("Open" for
// navigate, "Play" for play) when no custom label is set, so the user sees
// what the action will look like even before typing one. The action.kind
// drives click behavior independently — items are clickable whether or
// not the label renders.
const actionLabel = computed<string | null>(() => {
  if (!props.template.action.show) return null
  if (props.template.action.label && props.template.action.label.trim().length > 0) {
    return props.template.action.label
  }
  return props.template.action.kind === 'play' ? 'Play' : 'Open'
})

// Interactivity gate — only when an ancestor provided the click handler.
// Outside an interactive context (canvas, tree preview), the item is
// purely presentational and click-through is a no-op.
const interactive = computed(() => onItemClick != null)

function handleClick() {
  if (!onItemClick) return
  onItemClick({
    itemId: props.item.id,
    itemName: props.item.name,
    isCollection: props.item.isCollection === true,
    template: props.template,
    ownUiType: props.item.uiType,
    bindingOverride: props.item.bindingOverride ?? null,
  })
}

// Hash item identity to a hue so each placeholder gets its own color. Without
// this every tile looks like the same green blob and the preview is useless
// for distinguishing items. djb2-style hash; we don't need cryptographic
// quality, just deterministic spread across the wheel.
function hashHue(s: string): number {
  let h = 5381
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0
  return Math.abs(h) % 360
}

const hue = computed(() => {
  const seed = props.item.id ?? props.item.name ?? 'x'
  return hashHue(seed)
})

// Two HSL stops, both desaturated and dark, with the second a little
// darker and shifted slightly hue-wise. Produces calm, designer-y
// gradients instead of garish color blocks.
const placeholderBg = computed(() => {
  const h1 = hue.value
  const h2 = (h1 + 18) % 360
  return `linear-gradient(135deg, hsl(${h1} 32% 38%), hsl(${h2} 36% 26%))`
})

// First letter of item name, used as a subtle glyph on placeholder tiles.
// Falls back to a generic dot when the item has no name yet.
const placeholderGlyph = computed(() => {
  const n = props.item.name?.trim()
  if (!n) return '·'
  return n.charAt(0).toUpperCase()
})
</script>

<template>
  <!-- Tile: image-dominant, label overlay or below -->
  <div
    v-if="template.variant === 'tile'"
    class="lip lip-tile"
    :class="{ selected, interactive }"
    :style="{ aspectRatio: aspect, '--lip-bg': placeholderBg }"
    @click="handleClick"
  >
    <img
      v-if="item.imageUrl && template.image.placement === 'background'"
      :src="item.imageUrl"
      class="lip-img-bg"
      alt=""
    >
    <!-- No image: show the item's NAME centered as the placeholder content
         (real content, not just a letter glyph). The overlay title below is
         only for laying the name over an actual image. -->
    <span
      v-if="!item.imageUrl"
      class="lip-ph-name"
    >{{ item.name || '(untitled)' }}</span>
    <div class="lip-scrim" />
    <span
      v-if="item.imageUrl && template.title.show && template.title.placement === 'overlay'"
      class="lip-title lip-title--overlay"
      :style="{ WebkitLineClamp: titleLines, lineClamp: titleLines }"
    >{{ item.name }}</span>
    <span v-if="badgeText" class="lip-badge">{{ badgeText }}</span>
    <span v-if="actionLabel" class="lip-action lip-action--corner">{{ actionLabel }} →</span>
    <div v-if="progressPct !== null" class="lip-progress">
      <div class="lip-progress-fill" :style="{ width: progressPct + '%' }" />
    </div>
  </div>

  <!-- Card: image on top, title + subtitle below -->
  <div
    v-else-if="template.variant === 'card'"
    class="lip lip-card"
    :class="{ selected, interactive }"
    :style="{ '--lip-bg': placeholderBg }"
    @click="handleClick"
  >
    <div
      v-if="template.image.placement === 'top' || template.image.placement === 'background'"
      class="lip-card-img"
      :style="{ aspectRatio: aspect }"
    >
      <img v-if="item.imageUrl" :src="item.imageUrl" alt="">
      <span v-else class="lip-glyph">{{ placeholderGlyph }}</span>
      <span v-if="badgeText" class="lip-badge">{{ badgeText }}</span>
    </div>
    <div class="lip-card-body">
      <span
        v-if="template.title.show"
        class="lip-title lip-title--card"
        :style="{ WebkitLineClamp: titleLines, lineClamp: titleLines }"
      >{{ item.name }}</span>
      <span v-if="subtitleText" class="lip-subtitle">{{ subtitleText }}</span>
      <div v-if="progressPct !== null" class="lip-progress lip-progress--inline">
        <div class="lip-progress-fill" :style="{ width: progressPct + '%' }" />
      </div>
      <span v-if="actionLabel" class="lip-action">{{ actionLabel }} →</span>
    </div>
  </div>

  <!-- Row: compact, thumbnail + label -->
  <div
    v-else-if="template.variant === 'row'"
    class="lip lip-row"
    :class="{ selected, interactive }"
    :style="{ '--lip-bg': placeholderBg }"
    @click="handleClick"
  >
    <template v-if="template.image.placement !== 'none'">
      <div class="lip-row-thumb" :style="{ aspectRatio: aspect }">
        <img v-if="item.imageUrl" :src="item.imageUrl" alt="">
        <span v-else class="lip-glyph lip-glyph--sm">{{ placeholderGlyph }}</span>
      </div>
    </template>
    <span class="lip-title lip-title--row">{{ item.name }}</span>
    <span v-if="badgeText" class="lip-badge lip-badge--inline">{{ badgeText }}</span>
    <span v-if="actionLabel" class="lip-action lip-action--row">{{ actionLabel }} →</span>
  </div>

  <!-- Detail Row: rich row with image + multi-line text + badges + progress -->
  <div
    v-else-if="template.variant === 'detail-row'"
    class="lip lip-detail-row"
    :class="{ selected, interactive }"
    :style="{ '--lip-bg': placeholderBg }"
    @click="handleClick"
  >
    <template v-if="template.image.placement !== 'none'">
      <div class="lip-detail-thumb" :style="{ aspectRatio: aspect }">
        <img v-if="item.imageUrl" :src="item.imageUrl" alt="">
        <span v-else class="lip-glyph">{{ placeholderGlyph }}</span>
      </div>
    </template>
    <div class="lip-detail-body">
      <div class="lip-detail-row-top">
        <span
          v-if="template.title.show"
          class="lip-title lip-title--card"
          :style="{ WebkitLineClamp: titleLines, lineClamp: titleLines }"
        >{{ item.name }}</span>
        <span v-if="badgeText" class="lip-badge lip-badge--inline">{{ badgeText }}</span>
      </div>
      <span v-if="subtitleText" class="lip-subtitle">{{ subtitleText }}</span>
      <div v-if="progressPct !== null" class="lip-progress lip-progress--inline">
        <div class="lip-progress-fill" :style="{ width: progressPct + '%' }" />
      </div>
      <span v-if="actionLabel" class="lip-action lip-action--detail">{{ actionLabel }} →</span>
    </div>
  </div>

  <!-- Hero card: large featured-style item -->
  <div
    v-else-if="template.variant === 'hero-card'"
    class="lip lip-hero"
    :class="{ selected, interactive }"
    :style="{ aspectRatio: aspect, '--lip-bg': placeholderBg }"
    @click="handleClick"
  >
    <img
      v-if="item.imageUrl"
      :src="item.imageUrl"
      class="lip-img-bg"
      alt=""
    >
    <span v-if="!item.imageUrl" class="lip-glyph lip-glyph--hero">{{ placeholderGlyph }}</span>
    <div class="lip-scrim lip-scrim--strong" />
    <div class="lip-hero-content">
      <span v-if="badgeText" class="lip-badge lip-badge--floating">{{ badgeText }}</span>
      <span
        v-if="template.title.show"
        class="lip-title lip-title--hero"
        :style="{ WebkitLineClamp: titleLines, lineClamp: titleLines }"
      >{{ item.name }}</span>
      <span v-if="subtitleText" class="lip-subtitle lip-subtitle--hero">{{ subtitleText }}</span>
      <div v-if="progressPct !== null" class="lip-progress lip-progress--hero">
        <div class="lip-progress-fill" :style="{ width: progressPct + '%' }" />
      </div>
      <span v-if="actionLabel" class="lip-action lip-action--hero">{{ actionLabel }} →</span>
    </div>
  </div>
</template>

<style scoped>
/*
 * LIP = Library Item Preview. One component renders every variant; each
 * variant has its own class that overrides defaults. Everything is sized
 * by its container — pass an explicit width on the parent (e.g. canvas
 * 200px peek; carousel cell ~110px) and the variant fills it.
 *
 * Placeholder color: --lip-bg is computed from a hash of item.id, so each
 * tile has its own muted gradient — distinguishable at a glance instead of
 * a sea of identical green blocks.
 */
.lip {
  position: relative;
  border-radius: 12px;
  overflow: hidden;
  background: var(--lip-bg, linear-gradient(135deg, hsl(160 28% 35%), hsl(160 32% 24%)));
  /* Subtle elevation lifts the preview off the canvas/preview surface. */
  box-shadow:
    0 1px 0 color-mix(in oklch, white 6%, transparent) inset,
    0 1px 2px rgba(0, 0, 0, 0.25);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Inter, Roboto, sans-serif;
  -webkit-font-smoothing: antialiased;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.lip.selected {
  box-shadow:
    0 0 0 2px color-mix(in oklch, var(--brand-2, #6b8f71) 80%, transparent),
    0 1px 0 color-mix(in oklch, white 6%, transparent) inset,
    0 4px 12px rgba(0, 0, 0, 0.35);
}

.lip-img-bg {
  position: absolute; inset: 0;
  width: 100%; height: 100%;
  object-fit: cover;
}

/* Scrim: a smooth dark gradient ONLY at the bottom (where the title sits)
 * so titles are readable but the placeholder color stays visible. Stronger
 * variant for hero where there's more content to anchor. */
.lip-scrim {
  position: absolute; inset: 0;
  background: linear-gradient(180deg,
    transparent 35%,
    rgba(0, 0, 0, 0.15) 60%,
    rgba(0, 0, 0, 0.6) 100%);
  pointer-events: none;
}
.lip-scrim--strong {
  background: linear-gradient(180deg,
    rgba(0, 0, 0, 0.05) 0%,
    rgba(0, 0, 0, 0.25) 50%,
    rgba(0, 0, 0, 0.75) 100%);
}

/* Placeholder glyph — the first letter of the item name, set in a large
 * thin weight so it reads as a quiet identifier rather than as content. */
.lip-glyph {
  position: absolute; inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48px;
  font-weight: 200;
  color: rgba(255, 255, 255, 0.55);
  letter-spacing: -0.02em;
  pointer-events: none;
}
.lip-glyph--sm {
  font-size: 20px;
}
.lip-glyph--hero {
  font-size: 72px;
}

/* Image-less placeholder showing the real item name, centered on the tile's
 * color. Used where the image fills the whole tile (tile variant) so an
 * item without imagery still reads as real content rather than a lone glyph. */
.lip-ph-name {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
  text-align: center;
  color: rgba(255, 255, 255, 0.92);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.25;
  letter-spacing: -0.01em;
  overflow: hidden;
  pointer-events: none;
  z-index: 1;
}

/* Titles & subtitles */
.lip-title {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-weight: 600;
  color: white;
  line-height: 1.2;
  letter-spacing: -0.005em;
}
.lip-title--overlay {
  position: absolute; left: 10px; right: 10px; bottom: 8px;
  font-size: 13.5px;
  z-index: 1;
}
.lip-title--hero {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.015em;
}
.lip-title--card {
  color: var(--fg-1, #1a1a1a);
  font-size: 13.5px;
}
.lip-title--row {
  flex: 1;
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-1, #2c2c2c);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lip-subtitle {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  font-size: 12px;
  color: var(--fg-3, #888);
  line-height: 1.35;
  font-weight: 400;
}
.lip-subtitle--hero {
  color: rgba(255, 255, 255, 0.78);
}

/* Badge */
.lip-badge {
  position: absolute; top: 8px; left: 8px;
  font-size: 10px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.94);
  color: #2c2c2c;
  padding: 2px 7px;
  border-radius: 999px;
  z-index: 1;
  letter-spacing: 0.02em;
}
.lip-badge--inline {
  position: static;
  flex-shrink: 0;
}
.lip-badge--floating {
  position: static;
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.2);
  color: white;
  backdrop-filter: blur(8px);
}

/* Progress */
.lip-progress {
  position: absolute; left: 0; right: 0; bottom: 0;
  height: 3px;
  background: rgba(255, 255, 255, 0.22);
  z-index: 1;
}
.lip-progress-fill {
  height: 100%;
  background: linear-gradient(90deg,
    color-mix(in oklch, var(--brand-2, #6b8f71) 90%, white),
    var(--brand-2, #6b8f71));
  transition: width 0.2s;
}
.lip-progress--inline {
  position: relative;
  height: 3px;
  border-radius: 999px;
  background: color-mix(in oklch, var(--fg-3, #888) 22%, transparent);
}
.lip-progress--hero {
  position: relative;
  height: 3px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.25);
  margin-top: 4px;
}

/* Action label — by default a small inline tag at the bottom of card-style
 * variants. Variants that have no natural slot (tile, row, hero) get
 * positioned variants. */
.lip-action {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--fg-2, #555);
  letter-spacing: 0.01em;
}
.lip-action--detail { margin-top: 2px; }
.lip-action--corner {
  position: absolute;
  right: 8px;
  top: 8px;
  background: rgba(255, 255, 255, 0.92);
  color: #1f1f1f;
  padding: 2px 7px;
  border-radius: 999px;
  font-size: 10px;
  z-index: 1;
}
.lip-action--row {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--fg-3, #888);
}
.lip-action--hero {
  color: white;
  font-size: 12px;
  margin-top: 4px;
  text-shadow: 0 1px 3px rgba(0,0,0,0.5);
}

/* Interactive: cursor + hover lift when an ancestor wired the click
 * handler. Without an ancestor providing one, the item stays purely
 * presentational (canvas uses its own wrapper for selection). */
.lip.interactive {
  cursor: pointer;
}
.lip.interactive:hover {
  transform: translateY(-1px);
  box-shadow:
    0 1px 0 color-mix(in oklch, white 6%, transparent) inset,
    0 4px 12px rgba(0, 0, 0, 0.32);
}
.lip.interactive:active {
  transform: translateY(0);
  box-shadow:
    0 1px 0 color-mix(in oklch, white 6%, transparent) inset,
    0 1px 2px rgba(0, 0, 0, 0.25);
}

/* ── Tile ── */
.lip-tile {
  display: flex; align-items: flex-end; justify-content: flex-start;
}

/* ── Card ── */
.lip-card {
  background: var(--bg-1, #fff);
  border: 1px solid color-mix(in oklch, var(--fg-3, #888) 12%, transparent);
  display: flex; flex-direction: column;
  box-shadow:
    0 1px 2px rgba(0, 0, 0, 0.12),
    0 0 0 1px color-mix(in oklch, var(--fg-3) 6%, transparent);
}
.lip-card-img {
  position: relative;
  background: var(--lip-bg, linear-gradient(135deg, #7a9f80, #5a7d60));
  overflow: hidden;
}
.lip-card-img img {
  position: absolute; inset: 0;
  width: 100%; height: 100%;
  object-fit: cover;
}
.lip-card-body {
  padding: 10px 12px 12px;
  display: flex; flex-direction: column; gap: 4px;
}

/* ── Row ── */
.lip-row {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 10px;
  background: transparent;
  border-radius: 8px;
  box-shadow: none;
}
.lip-row-thumb {
  position: relative;
  width: 36px;
  background: var(--lip-bg, linear-gradient(135deg, #7a9f80, #5a7d60));
  border-radius: 6px;
  flex-shrink: 0;
  overflow: hidden;
}
.lip-row-thumb img {
  position: absolute; inset: 0;
  width: 100%; height: 100%;
  object-fit: cover;
}

/* ── Detail Row ── */
.lip-detail-row {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 10px 12px;
  background: var(--bg-1, #fff);
  border-radius: 10px;
  border: 1px solid color-mix(in oklch, var(--fg-3, #888) 12%, transparent);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}
.lip-detail-thumb {
  position: relative;
  width: 56px;
  background: var(--lip-bg, linear-gradient(135deg, #7a9f80, #5a7d60));
  border-radius: 8px;
  flex-shrink: 0;
  overflow: hidden;
}
.lip-detail-thumb img {
  position: absolute; inset: 0;
  width: 100%; height: 100%;
  object-fit: cover;
}
.lip-detail-body {
  flex: 1; min-width: 0;
  display: flex; flex-direction: column; gap: 4px;
}
.lip-detail-row-top {
  display: flex; justify-content: space-between; gap: 8px; align-items: flex-start;
}

/* ── Hero card ── */
.lip-hero {
  position: relative;
  display: flex; align-items: flex-end;
}
.lip-hero-content {
  position: relative;
  z-index: 1;
  padding: 16px;
  display: flex; flex-direction: column; gap: 6px;
  width: 100%;
}
</style>
