<script setup lang="ts">
const route = useRoute()
const sidebarOpen = ref(false)
const { isDark, toggle: toggleTheme } = useTheme()

defineProps<{
  title: string
  subtitle: string
  home: string
  overviewTo: string
  overviewLabel: string
  sourceUrl?: string
  groups: { title: string, links: { label: string, to: string }[] }[]
}>()

watch(() => route.path, () => {
  sidebarOpen.value = false
})
</script>

<template>
  <div class="reference-root">
    <div class="reference-atmosphere" />

    <header class="reference-header">
      <button
        class="reference-menu"
        type="button"
        :aria-expanded="sidebarOpen"
        :aria-label="`Toggle ${title} navigation`"
        @click="sidebarOpen = !sidebarOpen"
      >
        <Icon
          name="menu"
          :size="18"
        />
      </button>

      <NuxtLink
        :to="home"
        class="reference-brand"
      >
        <BoscaMark :size="23" />
        <span class="reference-brand-name">Bosca</span>
        <span class="reference-brand-separator" />
        <span class="reference-brand-product">{{ title }}</span>
      </NuxtLink>

      <div class="reference-actions">
        <a
          v-if="sourceUrl"
          :href="sourceUrl"
          class="reference-github"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="GitHub"
          title="GitHub"
        >
          <GitHubIcon />
        </a>
        <NuxtLink
          :to="overviewTo"
          class="reference-overview"
        >
          {{ overviewLabel }}
        </NuxtLink>
        <button
          class="reference-theme"
          type="button"
          :aria-label="isDark ? 'Switch to light mode' : 'Switch to dark mode'"
          :title="isDark ? 'Switch to light mode' : 'Switch to dark mode'"
          @click="toggleTheme"
        >
          <Icon
            :name="isDark ? 'sun' : 'moon'"
            :size="16"
          />
        </button>
      </div>
    </header>

    <Transition name="reference-scrim">
      <button
        v-if="sidebarOpen"
        class="reference-scrim"
        type="button"
        :aria-label="`Close ${title} navigation`"
        @click="sidebarOpen = false"
      />
    </Transition>

    <aside
      class="reference-sidebar"
      :class="{ open: sidebarOpen }"
      :aria-label="title"
    >
      <div class="reference-sidebar-heading">
        <span class="reference-sidebar-icon">
          <Icon
            name="book-open"
            :size="17"
          />
        </span>
        <span>
          <strong>{{ title }}</strong>
          <small>{{ subtitle }}</small>
        </span>
      </div>

      <div
        v-for="group in groups"
        :key="group.title"
        class="reference-group"
      >
        <div class="reference-group-title">
          {{ group.title }}
        </div>
        <NuxtLink
          v-for="link in group.links"
          :key="link.to"
          :to="link.to"
          class="reference-nav-link"
          :class="{ active: route.path === link.to }"
          :aria-current="route.path === link.to ? 'page' : undefined"
        >
          {{ link.label }}
        </NuxtLink>
      </div>
    </aside>

    <main class="reference-main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.reference-root {
  --reference-sidebar: 264px;
  --reference-header: 60px;
  position: relative;
  min-height: 100vh;
}

.reference-atmosphere {
  position: fixed;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background:
    radial-gradient(72% 48% at 8% -8%, color-mix(in oklch, #38bdf8 22%, transparent), transparent 62%),
    radial-gradient(56% 40% at 100% 4%, color-mix(in oklch, var(--brand-accent) 14%, transparent), transparent 68%),
    linear-gradient(180deg, color-mix(in oklch, #38bdf8 4%, var(--bg-0)) 0%, var(--bg-0) 42%);
}

.reference-header {
  position: fixed;
  inset: 0 0 auto;
  z-index: 40;
  height: var(--reference-header);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 22px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 72%, transparent);
  background: color-mix(in oklch, var(--bg-0) 88%, transparent);
  backdrop-filter: blur(16px);
}

.reference-menu {
  display: none;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-1);
  color: var(--fg-1);
  cursor: pointer;
}

.reference-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--fg-0);
  text-decoration: none;
}

.reference-brand-name {
  font-size: 15px;
  font-weight: 700;
}

.reference-brand-separator {
  width: 1px;
  height: 17px;
  background: var(--fg-4);
}

.reference-brand-product {
  color: #38bdf8;
  font-size: 14px;
  font-weight: 650;
}

.reference-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}

.reference-overview {
  color: var(--fg-2);
  font-size: 13px;
  font-weight: 550;
  text-decoration: none;
}

.reference-github {
  color: var(--fg-2);
  font-size: 13px;
  text-decoration: none;
}

.reference-overview:hover,
.reference-github:hover {
  color: #38bdf8;
}

.reference-theme {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--bg-1);
  color: var(--fg-2);
  cursor: pointer;
}

.reference-theme:hover {
  border-color: color-mix(in srgb, #38bdf8 55%, var(--line));
  color: var(--fg-0);
}

.reference-sidebar {
  position: fixed;
  top: var(--reference-header);
  bottom: 0;
  left: 0;
  z-index: 20;
  width: var(--reference-sidebar);
  padding: 22px 16px 32px;
  overflow-y: auto;
  border-right: 1px solid color-mix(in oklch, var(--line) 65%, transparent);
  background: color-mix(in oklch, var(--bg-0) 78%, transparent);
}

.reference-sidebar-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 9px 20px;
}

.reference-sidebar-heading > span:last-child {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.reference-sidebar-heading strong {
  color: var(--fg-0);
  font-size: 14px;
  font-weight: 650;
}

.reference-sidebar-heading small {
  color: var(--fg-3);
  font-size: 11px;
}

.reference-sidebar-icon {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid color-mix(in srgb, #38bdf8 38%, transparent);
  border-radius: 9px;
  background: color-mix(in srgb, #38bdf8 12%, transparent);
  color: #38bdf8;
}

.reference-group {
  margin-bottom: 22px;
}

.reference-group-title {
  padding: 0 10px 6px;
  color: var(--fg-3);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

.reference-nav-link {
  display: block;
  padding: 7px 10px;
  border-radius: var(--r-sm);
  color: var(--fg-2);
  font-size: 13.5px;
  text-decoration: none;
  transition: color 0.15s ease, background 0.15s ease;
}

.reference-nav-link:hover {
  background: var(--bg-2);
  color: var(--fg-0);
}

.reference-nav-link.active {
  background: color-mix(in srgb, #38bdf8 12%, transparent);
  color: #38bdf8;
}

.reference-main {
  min-height: 100vh;
  margin-left: var(--reference-sidebar);
  padding-top: var(--reference-header);
}

.reference-main :deep(.doc-content) {
  max-width: 920px;
}

.reference-main :deep(h1) {
  margin-top: 0;
}

.reference-scrim {
  position: fixed;
  inset: 0;
  z-index: 45;
  border: 0;
  background: color-mix(in srgb, #000 48%, transparent);
}

.reference-scrim-enter-active,
.reference-scrim-leave-active {
  transition: opacity 0.2s ease;
}

.reference-scrim-enter-from,
.reference-scrim-leave-to {
  opacity: 0;
}

@media (min-width: 901px) {
  .reference-scrim {
    display: none;
  }
}

@media (max-width: 900px) {
  .reference-header {
    padding: 0 14px;
  }

  .reference-menu {
    display: inline-flex;
  }

  .reference-sidebar {
    top: 0;
    z-index: 50;
    width: min(300px, 84vw);
    padding-top: 18px;
    background: var(--bg-0);
    box-shadow: 12px 0 40px -18px rgba(0, 0, 0, 0.55);
    transform: translateX(-108%);
    transition: transform 0.24s ease;
  }

  .reference-sidebar.open {
    transform: translateX(0);
  }

  .reference-main {
    margin-left: 0;
  }
}

@media (max-width: 560px) {
  .reference-brand-name,
  .reference-brand-separator,
  .reference-overview {
    display: none;
  }
}
</style>
