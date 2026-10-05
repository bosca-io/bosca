<script setup lang="ts">
// The Recommendations marketing page family. Rendered as link cards on every
// page in the family; the current route is excluded automatically so each page
// offers only its siblings.
const PAGES = [
  {
    to: '/discover/recommendations',
    icon: 'wand',
    title: 'Recommendations overview',
    sub: 'How candidates are generated, ranked live, and tuned by feedback.'
  },
  {
    to: '/discover/recommendations/strategies',
    icon: 'sliders',
    title: 'Strategies',
    sub: 'The candidate generators — trending, co-engagement, and the live model.'
  },
  {
    to: '/discover/recommendations/placements',
    icon: 'layout-grid',
    title: 'Placements',
    sub: 'Named surfaces that assemble a ranked set for any spot in your app.'
  },
  {
    to: '/discover/recommendations/signals',
    icon: 'target',
    title: 'Personalization signals',
    sub: 'Turn profile attributes and segments into what personalizes a feed.'
  },
  {
    to: '/discover/recommendations/model',
    icon: 'brain',
    title: 'The model',
    sub: 'Full-data training, automatic publication, version control, and built-in A/B testing.'
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
      <h2>More on <em>Recommendations</em></h2>
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
