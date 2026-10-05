<script setup lang="ts">
// The Analytics marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/analytics',
    icon: 'pulse',
    title: 'Analytics overview',
    sub: 'How events become queries, charts, and dashboards.'
  },
  {
    to: '/discover/analytics/ingestion',
    icon: 'database',
    title: 'Ingestion',
    sub: 'Events arrive from your apps and land in the warehouse — inspectable live.'
  },
  {
    to: '/discover/analytics/queries',
    icon: 'search',
    title: 'Queries',
    sub: 'Saved SQL with typed parameters — the single source of truth for every chart.'
  },
  {
    to: '/discover/analytics/dashboards',
    icon: 'layout-grid',
    title: 'Visualizations & dashboards',
    sub: 'Bind a query to a chart, then lay charts out on a grid that shares parameters.'
  },
  {
    to: '/discover/analytics/error-tracking',
    icon: 'alert',
    title: 'Error tracking',
    sub: 'Grouped errors, stack traces, and AI-generated root-cause hints.'
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
      <h2>More on <em>Analytics</em></h2>
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
