<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import OverflowMenu from './OverflowMenu.vue'
import Icon from './Icon.vue'

export type BreadcrumbItem = string | { label: string; to?: string }

interface OverflowMenuItem {
  id: string
  label: string
}

const props = withDefaults(defineProps<{
  title: string
  subtitle?: string
  breadcrumb?: BreadcrumbItem[]
  accent?: string
  tabs?: string[]
  activeTab?: string
}>(), {})

function crumbLabel(item: BreadcrumbItem): string {
  return typeof item === 'string' ? item : item.label
}

function crumbTo(item: BreadcrumbItem): string | undefined {
  return typeof item === 'string' ? undefined : item.to
}

const emit = defineEmits<{
  tab: [value: string]
}>()

// ── Tab overflow ─────────────────────────────────────────────────────────────
// Tabs are often facet-driven and unbounded; instead of wrapping or scrolling,
// show as many as fit and fold the rest into a "More" menu. A hidden duplicate
// row measures natural widths; a ResizeObserver tracks the available width.
const TAB_GAP = 4

const tabsRef = ref<HTMLElement>()
const measureRef = ref<HTMLElement>()
const tabWidths = ref<number[]>([])
const moreWidth = ref(0)
const availableWidth = ref(0)

function remeasure() {
  const container = tabsRef.value
  const measure = measureRef.value
  if (!container || !measure) return
  const style = getComputedStyle(container)
  availableWidth.value = container.clientWidth
    - parseFloat(style.paddingLeft || '0')
    - parseFloat(style.paddingRight || '0')
  const children = Array.from(measure.children) as HTMLElement[]
  moreWidth.value = children[children.length - 1]?.offsetWidth ?? 0
  tabWidths.value = children.slice(0, -1).map(el => el.offsetWidth)
}

let resizeObserver: ResizeObserver | undefined

function observeTabs() {
  if (!resizeObserver) return
  if (tabsRef.value) resizeObserver.observe(tabsRef.value)
  // The measure row resizes when tab labels change or fonts finish loading.
  if (measureRef.value) resizeObserver.observe(measureRef.value)
}

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => remeasure())
  }
  observeTabs()
  remeasure()
})

onBeforeUnmount(() => resizeObserver?.disconnect())

watch(() => props.tabs, async () => {
  await nextTick()
  observeTabs()
  remeasure()
}, { deep: true })

// Greedy fit; charges one gap per item (tabs + More) which is conservative by
// a single gap of slack.
function fitCount(widths: number[], reserved: number): number {
  let used = reserved
  let count = 0
  for (const w of widths) {
    if (used + w + TAB_GAP > availableWidth.value) break
    used += w + TAB_GAP
    count++
  }
  return count
}

const tabLayout = computed<{ visible: string[]; overflow: string[] }>(() => {
  const tabs = props.tabs ?? []
  const widths = tabWidths.value
  if (!tabs.length) return { visible: [], overflow: [] }
  // Until measured (SSR / first paint), show everything; the row clips rather
  // than wraps, and the mounted measurement collapses it immediately.
  if (widths.length !== tabs.length || availableWidth.value <= 0) {
    return { visible: tabs, overflow: [] }
  }
  const total = widths.reduce((sum, w) => sum + w, 0) + TAB_GAP * (tabs.length - 1)
  if (total <= availableWidth.value) return { visible: tabs, overflow: [] }

  let count = fitCount(widths, moreWidth.value)
  const activeIndex = props.activeTab ? tabs.indexOf(props.activeTab) : -1
  if (activeIndex >= 0 && activeIndex >= count) {
    // Keep the active tab visible: reserve its width, then promote it to the
    // end of the visible row.
    const activeWidth = widths[activeIndex] ?? 0
    count = Math.min(fitCount(widths.slice(0, activeIndex), moreWidth.value + activeWidth + TAB_GAP), activeIndex)
    const visible = [...tabs.slice(0, count), tabs[activeIndex]!]
    return { visible, overflow: tabs.filter(t => !visible.includes(t)) }
  }
  count = Math.max(1, count)
  return { visible: tabs.slice(0, count), overflow: tabs.slice(count) }
})

const overflowItems = computed<OverflowMenuItem[]>(() =>
  tabLayout.value.overflow.map(t => ({ id: t, label: t })),
)

function tabStyle(t: string) {
  return {
    color: t === props.activeTab ? 'var(--fg-0)' : 'var(--fg-3)',
    fontWeight: t === props.activeTab ? 500 : 400,
    borderBottom: `2px solid ${t === props.activeTab ? (props.accent || 'var(--brand-2)') : 'transparent'}`,
  }
}
</script>

<template>
  <div class="page-header">
    <div class="header-row">
      <div class="header-left">
        <!-- Breadcrumb -->
        <div
          v-if="breadcrumb && breadcrumb.length"
          class="breadcrumb"
        >
          <template v-for="(crumb, i) in breadcrumb" :key="i">
            <span v-if="i > 0" class="breadcrumb-sep">/</span>
            <NuxtLink
              v-if="crumbTo(crumb)"
              :to="crumbTo(crumb)"
              class="breadcrumb-link"
            >{{ crumbLabel(crumb) }}</NuxtLink>
            <span v-else :style="{ color: i === breadcrumb.length - 1 ? 'var(--fg-1)' : 'var(--fg-3)' }">{{ crumbLabel(crumb) }}</span>
          </template>
        </div>

        <!-- Title row -->
        <div class="title-row">
          <h1 class="title">
            <slot name="title">{{ title }}</slot>
          </h1>
          <span v-if="subtitle && !$slots.subtitle" class="subtitle">{{ subtitle }}</span>
        </div>

        <!-- Subtitle row (only rendered when the slot is used; the inline subtitle prop still works above) -->
        <div v-if="$slots.subtitle" class="subtitle-row">
          <slot name="subtitle" />
        </div>
      </div>

      <!-- Actions slot -->
      <div class="actions">
        <slot name="actions" />
      </div>
    </div>

    <!-- Tabs -->
    <div
      v-if="tabs && tabs.length"
      ref="tabsRef"
      class="tabs"
    >
      <button
        v-for="t in tabLayout.visible"
        :key="t"
        class="tab"
        :style="tabStyle(t)"
        @click="emit('tab', t)"
      >
        {{ t }}
      </button>
      <!-- anchor right so the menu opens leftward over the tabs; the clipped
           container would cut off a left-anchored menu extending past it -->
      <OverflowMenu
        v-if="tabLayout.overflow.length"
        :items="overflowItems"
        anchor="right"
        @select="emit('tab', $event)"
      >
        <template #default="{ toggle }">
          <button class="tab tab-more" @click="toggle">
            More
            <Icon name="chevron-down" :size="12" color="var(--fg-3)" />
          </button>
        </template>
      </OverflowMenu>

      <!-- Hidden duplicate row used purely for width measurement -->
      <div ref="measureRef" class="tabs-measure" aria-hidden="true">
        <span v-for="t in tabs" :key="t" class="tab">{{ t }}</span>
        <span class="tab tab-more">
          More
          <Icon name="chevron-down" :size="12" color="var(--fg-3)" />
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  background: transparent;
  flex: 0 0 auto;
}

.header-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 18px 12px;
}

.header-left {
  flex: 1;
  min-width: 0;
}

.breadcrumb {
  font-size: 12px;
  color: var(--fg-3);
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.breadcrumb-sep {
  color: var(--fg-4);
}

.breadcrumb-link {
  color: var(--fg-3);
  text-decoration: none;
}

.breadcrumb-link:hover {
  color: var(--fg-1);
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.subtitle {
  font-size: 13px;
  color: var(--fg-3);
}

.subtitle-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 13px;
  color: var(--fg-2);
  min-width: 0;
}

.actions {
  display: flex;
  gap: 8px;
}

.tabs {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 22px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 55%, transparent);
  overflow-x: clip;
}

.tab {
  padding: 10px 12px;
  font-size: 13px;
  background: none;
  border: none;
  margin-bottom: -1px;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}

.tab-more {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--fg-3);
  border-bottom: 2px solid transparent;
}

.tab-more:hover {
  color: var(--fg-1);
}

.tabs-measure {
  position: absolute;
  top: 0;
  left: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  visibility: hidden;
  pointer-events: none;
  height: 0;
  overflow: hidden;
}
</style>
