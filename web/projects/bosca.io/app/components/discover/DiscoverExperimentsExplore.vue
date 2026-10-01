<script setup lang="ts">
// The Experiments marketing page family. Rendered as link cards on every page
// in the family; the current route is excluded automatically so each page
// offers only its siblings.
const PAGES = [
  {
    to: '/discover/experiments',
    icon: 'flask',
    title: 'Experiments overview',
    sub: 'Flags, A/B tests, rollouts, and results — one system.'
  },
  {
    to: '/discover/experiments/feature-flags',
    icon: 'flag',
    title: 'Feature Flags',
    sub: 'Typed flags with targeting rules and deterministic bucketing.'
  },
  {
    to: '/discover/experiments/ab-tests',
    icon: 'beaker',
    title: 'A/B Experiments',
    sub: 'Measure one variation against another, with sticky assignment.'
  },
  {
    to: '/discover/experiments/rollout',
    icon: 'trending-up',
    title: 'Rollout Policies',
    sub: 'Ramp the winner automatically, with guardrails that halt on regression.'
  },
  {
    to: '/discover/experiments/results',
    icon: 'scale',
    title: 'Results & Analysis',
    sub: 'Real statistics from your analytics, with a ship / don\'t-ship call.'
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
      <h2>More on <em>Experiments</em></h2>
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
