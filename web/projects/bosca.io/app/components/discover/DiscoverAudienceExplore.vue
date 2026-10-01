<script setup lang="ts">
// The Audience marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/audience',
    icon: 'target',
    title: 'Audience overview',
    sub: 'The people layer — from a profile to a targeted campaign.'
  },
  {
    to: '/discover/audience/profiles',
    icon: 'user',
    title: 'Profiles',
    sub: 'Multi-source typed attributes, identity, and visibility.'
  },
  {
    to: '/discover/audience/organizations',
    icon: 'building',
    title: 'Organizations',
    sub: 'Formal groups with members, managers, and flexible signup.'
  },
  {
    to: '/discover/audience/segments',
    icon: 'filter',
    title: 'Segments',
    sub: 'Static lists and dynamic, analytics-backed audiences.'
  },
  {
    to: '/discover/audience/community',
    icon: 'users',
    title: 'Community',
    sub: 'Small groups, families, and custom groups with their own activities.'
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
      <h2>More on <em>Audience</em></h2>
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
