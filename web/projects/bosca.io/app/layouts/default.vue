<script setup lang="ts">
const navOpen = ref(false)
provide('navOpen', navOpen)

/* Mobile-only slide-in state for the doc sidebar (≤900px drawer). */
const sidebarOpen = ref(false)
provide('sidebarOpen', sidebarOpen)

const route = useRoute()
watch(() => route.path, () => {
  sidebarOpen.value = false
})

const { currentSection } = useSections()
const hasSidebar = computed(() => currentSection.value !== null)
/* Bosca green — the default background tint when no section is active. */
const DEFAULT_ACCENT = '#00dc82'
const accent = computed(() => currentSection.value?.accent ?? DEFAULT_ACCENT)
</script>

<template>
  <div class="app-root">
    <div
      class="ambient-gradient"
      :style="{
        background: `
          radial-gradient(80% 55% at 12% -5%, color-mix(in oklch, ${accent} 28%, transparent), transparent 60%),
          radial-gradient(60% 45% at 95% 8%, color-mix(in oklch, var(--brand-accent) 18%, transparent), transparent 65%),
          radial-gradient(70% 50% at 100% 100%, color-mix(in oklch, ${accent} 12%, transparent), transparent 70%),
          linear-gradient(180deg, color-mix(in oklch, ${accent} 6%, var(--bg-0)) 0%, var(--bg-0) 40%)
        `
      }"
    />
    <TopNav />
    <Transition name="sidebar-scrim">
      <div
        v-if="sidebarOpen"
        class="sidebar-scrim"
        @click="sidebarOpen = false"
      />
    </Transition>
    <DocSidebar />
    <main
      class="doc-main"
      :class="{ 'no-sidebar': !hasSidebar }"
    >
      <slot />
      <AppFooter />
    </main>
  </div>
</template>

<style scoped>
.app-root {
  position: relative;
  min-height: 100vh;
}

.ambient-gradient {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: -1;
}

.sidebar-scrim {
  position: fixed;
  inset: 0;
  z-index: 25;
  background: color-mix(in srgb, #000 45%, transparent);
}

/* The drawer only exists at mobile widths; never show its scrim on desktop. */
@media (min-width: 901px) {
  .sidebar-scrim {
    display: none;
  }
}

.sidebar-scrim-enter-active,
.sidebar-scrim-leave-active {
  transition: opacity 0.2s ease;
}

.sidebar-scrim-enter-from,
.sidebar-scrim-leave-to {
  opacity: 0;
}
</style>
