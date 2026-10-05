<script setup lang="ts">
// The Scripts marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/scripts',
    icon: 'code',
    title: 'Scripts overview',
    sub: 'Real Kotlin that runs inside the platform, five ways.'
  },
  {
    to: '/discover/scripts/authoring',
    icon: 'edit-2',
    title: 'Authoring',
    sub: 'Typed access to platform services, validated and compiled.'
  },
  {
    to: '/discover/scripts/library',
    icon: 'library',
    title: 'Script Library',
    sub: 'Versioned, permissioned, and sourced from Git.'
  },
  {
    to: '/discover/scripts/triggers',
    icon: 'zap',
    title: 'Triggers',
    sub: 'Run a script on a platform event, asynchronously.'
  },
  {
    to: '/discover/scripts/endpoints',
    icon: 'route',
    title: 'API Endpoints',
    sub: 'Publish a script as an HTTP endpoint, JSON in and out.'
  }
]

withDefaults(defineProps<{
  title?: string
}>(), {
  title: 'Keep exploring'
})

const route = useRoute()
const siblings = computed(() => PAGES.filter(p => p.to !== route.path))
</script>

<template>
  <section class="section explore">
    <div class="section-head reveal">
      <p class="kicker">
        {{ title }}
      </p>
      <h2>More on <em>Scripts</em></h2>
    </div>
    <div class="explore-grid reveal">
      <NuxtLink
        v-for="page in siblings"
        :key="page.to"
        :to="page.to"
        class="explore-card"
      >
        <span class="explore-icon">
          <Icon
            :name="page.icon"
            :size="16"
          />
        </span>
        <span class="explore-text">
          <h3>{{ page.title }}</h3>
          <p>{{ page.sub }}</p>
        </span>
        <Icon
          name="arrow-right"
          :size="15"
          class="explore-arrow"
        />
      </NuxtLink>
    </div>
  </section>
</template>
