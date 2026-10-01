<script setup lang="ts">
// The Pipelines marketing page family. Rendered as link cards on every page
// in the family; the current route is excluded automatically so each page
// offers only its siblings.
const PAGES = [
  {
    to: '/discover/pipelines',
    icon: 'workflow',
    title: 'Pipelines overview',
    sub: 'What a pipeline is, how it executes, and what it can do.'
  },
  {
    to: '/discover/pipelines/editor',
    icon: 'layout-grid',
    title: 'The editor',
    sub: 'A three-pane graph workspace with typed connections and versioned saves.'
  },
  {
    to: '/discover/pipelines/nodes',
    icon: 'boxes',
    title: 'The node vocabulary',
    sub: 'Transforms, routes, actions, and the nodes your domains contribute.'
  },
  {
    to: '/discover/pipelines/dry-runs',
    icon: 'flask',
    title: 'Dry runs',
    sub: 'Trace a sample event through the graph before anything goes live.'
  },
  {
    to: '/discover/pipelines/runs',
    icon: 'zap',
    title: 'Triggers & runs',
    sub: 'Event-triggered, durable execution with every run logged.'
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
      <h2>More on <em>Pipelines</em></h2>
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
