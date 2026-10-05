<script setup lang="ts">
const route = useRoute()
const sidebarOpen = ref(false)
const { isDark, toggle: toggleTheme } = useTheme()

const groups = [
  {
    title: 'Foundations',
    links: [
      { label: 'Prologue', to: '/recommendation-models/start-here' },
      { label: 'Events to observations', to: '/recommendation-models/observations' },
      { label: 'Build the datasets', to: '/recommendation-models/datasets' }
    ]
  },
  {
    title: 'Build the models',
    links: [
      { label: 'Content model', to: '/recommendation-models/content-model' },
      { label: 'Personalized towers', to: '/recommendation-models/towers' },
      { label: 'Train retrieval and ranking', to: '/recommendation-models/training' }
    ]
  },
  {
    title: 'Finish',
    links: [
      { label: 'Export and verify', to: '/recommendation-models/export' }
    ]
  }
]

watch(() => route.path, () => {
  sidebarOpen.value = false
})
</script>

<template>
  <div class="model-learning-root">
    <header class="model-learning-header">
      <button
        class="model-learning-menu"
        type="button"
        :aria-expanded="sidebarOpen"
        aria-label="Toggle recommendation model guide navigation"
        @click="sidebarOpen = !sidebarOpen"
      >
        <Icon
          name="menu"
          :size="18"
        />
      </button>
      <NuxtLink
        to="/recommendation-models/start-here"
        class="model-learning-brand"
      >
        <BoscaMark :size="23" />
        <span class="brand-name">Bosca</span>
        <span class="brand-separator" />
        <span class="brand-product">Recommendation Models</span>
      </NuxtLink>
      <div class="model-learning-actions">
        <a
          href="https://github.com/bosca-io/bosca"
          class="github-link"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="GitHub"
          title="GitHub"
        >
          <GitHubIcon />
        </a>
        <NuxtLink
          to="/discover/recommendations"
          class="overview-link"
        >
          Recommendations overview
        </NuxtLink>
        <button
          class="theme-button"
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

    <Transition name="model-learning-scrim">
      <button
        v-if="sidebarOpen"
        class="model-learning-scrim"
        type="button"
        aria-label="Close recommendation model guide navigation"
        @click="sidebarOpen = false"
      />
    </Transition>

    <aside
      class="model-learning-sidebar"
      :class="{ open: sidebarOpen }"
      aria-label="Recommendation model guide"
    >
      <div class="sidebar-heading">
        <span class="sidebar-icon"><Icon
          name="book-open"
          :size="17"
        /></span>
        <span><strong>Build the models</strong><small>A code learning guide</small></span>
      </div>
      <div
        v-for="group in groups"
        :key="group.title"
        class="sidebar-group"
      >
        <div class="group-title">
          {{ group.title }}
        </div>
        <NuxtLink
          v-for="link in group.links"
          :key="link.to"
          :to="link.to"
          class="sidebar-link"
          :class="{ active: route.path === link.to }"
          :aria-current="route.path === link.to ? 'page' : undefined"
        >
          {{ link.label }}
        </NuxtLink>
      </div>
    </aside>

    <main class="model-learning-main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.model-learning-root {
  --guide-sidebar: 264px;
  --guide-header: 60px;
  min-height: 100vh;
  background: radial-gradient(70% 45% at 8% -5%, color-mix(in oklch, #84c032 12%, transparent), transparent 65%);
}

.model-learning-header {
  position: fixed;
  inset: 0 0 auto;
  z-index: 40;
  height: var(--guide-header);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 22px;
  border-bottom: 1px solid var(--line);
  background: color-mix(in oklch, var(--bg-0) 90%, transparent);
  backdrop-filter: blur(16px);
}

.model-learning-menu {
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

.model-learning-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--fg-0);
  text-decoration: none;
}

.brand-name { font-size: 15px; font-weight: 700; }
.brand-separator { width: 1px; height: 17px; background: var(--fg-4); }
.brand-product { color: #84c032; font-size: 14px; font-weight: 650; }
.model-learning-actions { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.overview-link, .github-link { color: var(--fg-2); font-size: 13px; text-decoration: none; }
.overview-link:hover, .github-link:hover { color: #84c032; }

.theme-button {
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

.model-learning-sidebar {
  position: fixed;
  top: var(--guide-header);
  bottom: 0;
  left: 0;
  z-index: 20;
  width: var(--guide-sidebar);
  padding: 22px 16px 32px;
  overflow-y: auto;
  border-right: 1px solid var(--line);
  background: color-mix(in oklch, var(--bg-0) 84%, transparent);
}

.sidebar-heading { display: flex; align-items: center; gap: 10px; padding: 8px 9px 20px; }
.sidebar-heading > span:last-child { display: flex; flex-direction: column; gap: 2px; }
.sidebar-heading strong { color: var(--fg-0); font-size: 14px; font-weight: 650; }
.sidebar-heading small { color: var(--fg-3); font-size: 11px; }
.sidebar-icon {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid color-mix(in srgb, #84c032 38%, transparent);
  border-radius: 9px;
  background: color-mix(in srgb, #84c032 12%, transparent);
  color: #84c032;
}

.sidebar-group { margin-bottom: 22px; }
.group-title { padding: 0 10px 6px; color: var(--fg-3); font-size: 10.5px; font-weight: 700; letter-spacing: 0.09em; text-transform: uppercase; }
.sidebar-link { display: block; padding: 7px 10px; border-radius: var(--r-sm); color: var(--fg-2); font-size: 13.5px; text-decoration: none; }
.sidebar-link:hover { background: var(--bg-2); color: var(--fg-0); }
.sidebar-link.active { background: color-mix(in srgb, #84c032 12%, transparent); color: #84c032; }
.model-learning-main { min-height: 100vh; margin-left: var(--guide-sidebar); padding-top: var(--guide-header); }

.model-learning-scrim { position: fixed; inset: 0; z-index: 45; border: 0; background: color-mix(in srgb, #000 48%, transparent); }
.model-learning-scrim-enter-active, .model-learning-scrim-leave-active { transition: opacity 0.2s ease; }
.model-learning-scrim-enter-from, .model-learning-scrim-leave-to { opacity: 0; }

@media (min-width: 901px) { .model-learning-scrim { display: none; } }
@media (max-width: 900px) {
  .model-learning-header { padding: 0 14px; }
  .model-learning-menu { display: inline-flex; }
  .model-learning-sidebar {
    top: 0;
    z-index: 50;
    width: min(300px, 84vw);
    padding-top: 18px;
    background: var(--bg-0);
    box-shadow: 12px 0 40px -18px rgba(0, 0, 0, 0.55);
    transform: translateX(-108%);
    transition: transform 0.24s ease;
  }
  .model-learning-sidebar.open { transform: translateX(0); }
  .model-learning-main { margin-left: 0; }
}
@media (max-width: 560px) {
  .brand-name, .brand-separator, .overview-link { display: none; }
}
</style>
