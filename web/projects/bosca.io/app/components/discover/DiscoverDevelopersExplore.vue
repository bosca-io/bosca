<script setup lang="ts">
// The Developers marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/developers',
    icon: 'code',
    title: 'Build on Bosca',
    sub: 'The patterns Bosca is built from — and built with.'
  },
  {
    to: '/discover/developers/architecture',
    icon: 'layers',
    title: 'Architecture',
    sub: 'Core and implementation modules in one Gradle build, with compile-time wiring.'
  },
  {
    to: '/discover/developers/build',
    icon: 'boxes',
    title: 'Building a feature',
    sub: 'From a model to a GraphQL and REST API.'
  },
  {
    to: '/discover/developers/identity',
    icon: 'shield-check',
    title: 'Identity & access',
    sub: 'Principals, profiles, permissions, and organizations.'
  },
  {
    to: '/discover/developers/extend',
    icon: 'puzzle',
    title: 'Extending & automating',
    sub: 'Jobs, events, caching, scripting, and the CLI.'
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
      <h2>More for <em>developers</em></h2>
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
