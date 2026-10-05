<script setup lang="ts">
import { SUBSYSTEMS, accentHueShift } from '~/composables/useSubsystems'

import type { NavGroup } from '~~/shared/types'

const props = defineProps<{
  subsystem: string
  active: string
  accent: string
  variant?: 'hud' | 'glass'
  dynamicGroups?: NavGroup[]
}>()

const emit = defineEmits<{
  activate: [id: string]
  'brand-click': []
  'search-click': []
  home: []
}>()

const hueShift = computed(() => accentHueShift(props.subsystem))

const variant = computed(() => props.variant ?? 'glass')
const isGlass = computed(() => variant.value === 'glass')

const sys = computed(() => SUBSYSTEMS.find(s => s.id === props.subsystem))

const hoveredItem = ref<string | null>(null)
const navGroups = computed(() => {
  const staticGroups = sys.value?.nav.filter(g => !g.admin && g.group !== 'Settings' && g.group !== 'Configure') ?? []
  if (props.dynamicGroups?.length) {
    return [...props.dynamicGroups, ...staticGroups.filter(g => g.group !== 'Dashboards')]
  }
  return staticGroups
})
</script>

<template>
  <div
    v-if="sys"
    class="scoped-nav"
    :style="{
      background: isGlass ? 'transparent' : 'var(--bg-1)',
      borderRight: isGlass ? 'none' : '1px solid color-mix(in oklch, var(--line) 55%, transparent)',
    }"
  >
    <!-- Brand — returns to the home hub. -->
    <button
      type="button"
      class="scoped-nav-brand"
      aria-label="Bosca Studio home"
      @click="emit('home')"
    >
      <span
        class="scoped-nav-logo-wrap"
        :style="{ filter: `hue-rotate(${hueShift}deg)` }"
      >
        <svg
          width="22"
          height="22"
          viewBox="0 0 512 512"
          fill="none"
          aria-label="Bosca">
          <path d="M256 262L491 138L256 21L21 138L256 262Z" fill="#00c16a" />
          <path d="M491 138L256 262V498L491 372V138Z" fill="#00dc82" />
          <path d="M21 138L256 262V498L21 372V138Z" fill="#00a155" />
        </svg>
      </span>
      <span class="scoped-nav-brand-text">
        <span class="scoped-nav-brand-name">Bosca</span>
        <span class="scoped-nav-brand-separator" />
        <span class="scoped-nav-brand-studio">Studio</span>
      </span>
    </button>

    <!-- Subsystem header -->
    <div
      class="scoped-nav-header"
      role="button"
      tabindex="0"
      :style="{
        borderBottom: isGlass ? 'none' : '1px solid color-mix(in oklch, var(--line) 50%, transparent)',
      }"
      @click="emit('brand-click')"
    >
      <!-- HUD-only radial gradient -->
      <div
        v-if="!isGlass"
        class="scoped-nav-header-gradient"
        :style="{
          background: `radial-gradient(120% 100% at 0% 0%, color-mix(in oklch, ${sys.accent} 22%, transparent), transparent 60%)`,
        }"
      />
      <!-- Icon badge -->
      <div
        class="scoped-nav-icon-badge"
        :style="{
          borderRadius: isGlass ? '999px' : '11px',
          background: isGlass
            ? `color-mix(in oklch, ${sys.accent} 18%, transparent)`
            : `linear-gradient(135deg, ${sys.accent}, color-mix(in oklch, ${sys.accent} 60%, #000))`,
          border: isGlass ? `1px solid color-mix(in oklch, ${sys.accent} 35%, transparent)` : 'none',
          boxShadow: isGlass ? 'none' : `0 4px 14px -4px ${sys.accent}`,
        }"
      >
        <Icon :name="sys.icon" :size="16" :color="isGlass ? sys.accent : '#fff'" />
      </div>
      <div class="scoped-nav-header-text">
        <div class="scoped-nav-label">{{ sys.label }}</div>
        <div class="scoped-nav-sub">{{ sys.sub }}</div>
      </div>
      <Icon name="chevron" :size="14" class="scoped-nav-header-chevron" />
    </div>

    <!-- Omni search -->
    <button
      class="scoped-nav-search"
      title="Search (⌘K)"
      @click="emit('search-click')"
    >
      <Icon name="search" :size="14" color="var(--fg-3)" />
      <span class="scoped-nav-search-placeholder">Search…</span>
      <span class="scoped-nav-search-kbd mono">⌘K</span>
    </button>

    <!-- Nav groups -->
    <div class="scoped-nav-groups">
      <div
        v-for="group in navGroups"
        :key="group.group"
        class="scoped-nav-group"
      >
        <div class="scoped-nav-group-label">
          {{ group.group }}
        </div>
        <button
          v-for="item in group.items"
          :key="item.id"
          class="scoped-nav-item"
          :style="{
            color: item.id === active ? 'var(--fg-0)' : 'var(--fg-2)',
            background: item.id === active
              ? `color-mix(in oklch, ${sys.accent} 16%, transparent)`
              : hoveredItem === item.id ? 'var(--bg-3)' : 'transparent',
            fontWeight: item.id === active ? 500 : 400,
          }"
          @click="emit('activate', item.id)"
          @mouseenter="hoveredItem = item.id"
          @mouseleave="hoveredItem = null"
        >
          <!-- Active indicator bar -->
          <span
            v-if="item.id === active"
            class="scoped-nav-active-bar"
            :style="{
              background: sys.accent,
              boxShadow: `0 0 8px ${sys.accent}`,
            }"
          />
          <Icon :name="item.icon" :size="15" :color="item.id === active ? sys.accent : 'var(--fg-3)'" />
          <span class="scoped-nav-item-label">{{ item.label }}</span>
          <span
            v-if="item.count != null"
            class="scoped-nav-item-count mono tabular"
            :style="{
              background: item.id === active
                ? `color-mix(in oklch, ${sys.accent} 30%, transparent)`
                : 'var(--bg-3)',
              color: item.id === active ? '#fff' : 'var(--fg-2)',
            }"
          >{{ item.count.toLocaleString() }}</span>
        </button>
      </div>
    </div>

    <CustomBrand />
  </div>
</template>

<style scoped>
.scoped-nav {
  width: 224px;
  flex: 0 0 224px;
  display: flex;
  flex-direction: column;
  overflow: visible;
  position: relative;
}

.scoped-nav-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 30px 20px 23px;
  background: transparent;
  border: none;
  width: 100%;
  text-align: left;
  cursor: pointer;
  color: var(--fg-0);
  transition: opacity 0.15s ease;
}

.scoped-nav-brand:hover {
  opacity: 0.7;
}

.scoped-nav-brand:focus-visible {
  outline: 2px solid color-mix(in oklch, var(--brand-accent) 60%, transparent);
  outline-offset: -4px;
  border-radius: var(--r-sm);
}

.scoped-nav-logo-wrap {
  display: inline-flex;
  transition: filter .25s ease;
}

.scoped-nav-brand-text {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.scoped-nav-brand-name {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.scoped-nav-brand-separator {
  width: 1px;
  height: 18px;
  background: var(--fg-2);
  align-self: center;
}

.scoped-nav-brand-studio {
  font-size: 13px;
  color: var(--fg-1);
  font-weight: 500;
}

.scoped-nav-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 14px 12px 14px;
  padding: 7px 10px;
  border-radius: 9px;
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
  background: color-mix(in oklch, var(--bg-2) 55%, transparent);
  color: var(--fg-3);
  font-size: 12.5px;
  transition: background 0.15s, border-color 0.15s;
}

.scoped-nav-search:hover {
  background: color-mix(in oklch, var(--bg-3) 70%, transparent);
  border-color: color-mix(in oklch, var(--line) 90%, transparent);
}

.scoped-nav-search-placeholder {
  flex: 1;
  text-align: left;
}

.scoped-nav-search-kbd {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
}

.scoped-nav-header {
  padding: 12px 9px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  position: relative;
  overflow: hidden;
  cursor: pointer;
  border-radius: 10px;
  margin: 0 6px;
  transition: background 0.15s;
}

.scoped-nav-header:hover {
  background: color-mix(in oklch, var(--bg-3) 50%, transparent);
}

.scoped-nav-header-gradient {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.scoped-nav-icon-badge {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  flex: 0 0 32px;
}

.scoped-nav-header-chevron {
  margin-left: auto;
  color: var(--fg-3);
  opacity: 0.5;
  flex-shrink: 0;
  transition: opacity 0.15s;
}

.scoped-nav-header:hover .scoped-nav-header-chevron {
  opacity: 0.85;
}

.scoped-nav-header-text {
  position: relative;
  min-width: 0;
}

.scoped-nav-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-0);
  letter-spacing: -0.005em;
}

.scoped-nav-sub {
  font-size: 11px;
  color: var(--fg-3);
}

.scoped-nav-groups {
  flex: 1;
  overflow: auto;
  padding: 10px 0;
}

.scoped-nav-group {
  margin-bottom: 14px;
}

.scoped-nav-group-label {
  font-size: 10px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: .12em;
  font-weight: 700;
  padding: 4px 16px 6px;
}

.scoped-nav-item {
  width: calc(100% - 24px);
  margin: 1px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  font-size: 13px;
  border-radius: 9px;
  position: relative;
  transition: background .15s;
}

.scoped-nav-active-bar {
  position: absolute;
  left: -12px;
  top: 5px;
  bottom: 5px;
  width: 3px;
  border-radius: 2px;
}

.scoped-nav-item-label {
  flex: 1;
  text-align: left;
}

.scoped-nav-item-count {
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 999px;
  font-weight: 600;
}

</style>
