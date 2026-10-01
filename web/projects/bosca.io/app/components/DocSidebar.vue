<script setup lang="ts">
const route = useRoute()
const { currentSection } = useSections()

const navOpen = inject<Ref<boolean>>('navOpen')!
const sidebarOpen = inject<Ref<boolean>>('sidebarOpen')!
</script>

<template>
  <nav
    v-if="currentSection"
    class="doc-sidebar"
    :class="{ open: sidebarOpen }"
  >
    <button
      class="sidebar-section-header"
      @click="navOpen = true"
    >
      <div
        class="sidebar-section-icon"
        :style="{
          background: `color-mix(in oklch, ${currentSection.accent} 18%, transparent)`,
          border: `1px solid color-mix(in oklch, ${currentSection.accent} 35%, transparent)`
        }"
      >
        <Icon
          :name="currentSection.icon"
          :size="16"
          :color="currentSection.accent"
        />
      </div>
      <div class="sidebar-section-text">
        <div class="sidebar-section-label">
          {{ currentSection.label }}
        </div>
        <div class="sidebar-section-sub">
          {{ currentSection.sub }}
        </div>
      </div>
      <svg
        class="sidebar-section-chevron"
        width="6"
        height="10"
        viewBox="0 0 6 10"
        fill="none"
      >
        <path
          d="M1 1L5 5L1 9"
          stroke="currentColor"
          stroke-width="1.2"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </button>

    <div
      v-for="group in currentSection.groups"
      :key="group.title"
      class="nav-group"
    >
      <div class="nav-group-title">
        {{ group.title }}
      </div>
      <NuxtLink
        v-for="link in group.links"
        :key="link.to"
        :to="link.to"
        class="nav-link"
        :class="{ active: route.path === link.to }"
      >
        {{ link.label }}
      </NuxtLink>
    </div>
  </nav>
</template>
