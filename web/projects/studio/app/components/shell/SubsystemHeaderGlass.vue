<script setup lang="ts">
import { SUBSYSTEMS, accentHueShift } from '~/composables/useSubsystems'

const props = defineProps<{
  active: string
  accent: string
}>()

const emit = defineEmits<{
  change: [id: string]
}>()

const hueShift = computed(() => accentHueShift(props.active))

const paletteOpen = ref(false)

function onKey(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = true
  } else if (e.key === 'Escape') {
    paletteOpen.value = false
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))

function onPaletteJump(sub: string, view?: string) {
  if (view) {
    navigateTo(`/${sub}/${view}`)
  } else {
    emit('change', sub)
  }
  paletteOpen.value = false
}

function onPaletteNavigate(path: string) {
  navigateTo(path)
  paletteOpen.value = false
}
</script>

<template>
  <div class="header-glass">
    <!-- Brand block — fixed 224px width matching scoped nav -->
    <div class="header-brand">
      <span
        class="header-logo-wrap"
        :style="{
          filter: `hue-rotate(${hueShift}deg)`,
        }"
      >
        <!-- BoscaMark logo -->
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
      <span class="header-brand-text">
        <span class="header-brand-name">Bosca</span>
        <span class="header-brand-separator" />
        <span class="header-brand-studio">Studio</span>
      </span>
    </div>

    <!-- Subsystem switcher -->
    <div class="header-switcher">
      <button
        v-for="s in SUBSYSTEMS"
        :key="s.id"
        class="header-switcher-btn"
        :style="{
          fontWeight: s.id === active ? 550 : 450,
          color: s.id === active ? 'var(--fg-0)' : 'var(--fg-2)',
          background: s.id === active ? `color-mix(in oklch, ${s.accent} 14%, var(--bg-1))` : 'transparent',
          border: '1px solid ' + (s.id === active ? `color-mix(in oklch, ${s.accent} 26%, transparent)` : 'transparent'),
        }"
        @click="emit('change', s.id)"
      >
        <Icon :name="s.icon" :size="14" :color="s.id === active ? s.accent : 'var(--fg-3)'" />
        {{ s.label }}
      </button>
    </div>

    <span class="spacer" />

    <!-- Search button -->
    <button class="header-search-btn" title="Search (⌘K)" @click="paletteOpen = true">
      <Icon name="search" :size="14" color="var(--fg-2)" />
    </button>

    <!-- Profile menu -->
    <ProfileMenu />

    <CommandPalette
      :open="paletteOpen"
      :subsystem="active"
      @close="paletteOpen = false"
      @jump="onPaletteJump"
      @navigate="onPaletteNavigate"
    />
  </div>
</template>

<style scoped>
.header-glass {
  height: 76px;
  flex: 0 0 76px;
  display: flex;
  align-items: center;
  gap: 18px;
  position: relative;
  z-index: 5;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 40%, transparent);
}

.header-brand {
  width: 224px;
  flex: 0 0 224px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
}

.header-logo-wrap {
  display: inline-flex;
  transition: filter .25s ease;
}

.header-brand-text {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.header-brand-name {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.header-brand-separator {
  width: 1px;
  height: 18px;
  background: var(--fg-2);
  align-self: center;
}

.header-brand-studio {
  font-size: 13px;
  color: var(--fg-1);
  font-weight: 500;
}

.header-switcher {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  border-radius: 11px;
  background: color-mix(in oklch, var(--bg-2) 70%, transparent);
  backdrop-filter: blur(20px) saturate(1.15);
  -webkit-backdrop-filter: blur(20px) saturate(1.15);
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
}

.header-switcher-btn {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 5px 11px;
  border-radius: 8px;
  font-size: 12.5px;
}

.spacer {
  flex: 1;
}

.header-search-btn {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: 1px solid color-mix(in oklch, var(--line) 45%, transparent);
  color: var(--fg-2);
}

</style>
