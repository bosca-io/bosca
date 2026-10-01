<script setup lang="ts">
// The Studio marketing page family. Rendered as link cards on every page in the
// family; the current route is excluded automatically so each page offers only
// its siblings.
const PAGES = [
  {
    to: '/discover/studio',
    icon: 'monitor',
    title: 'Studio overview',
    sub: 'One app for every subsystem of the platform.'
  },
  {
    to: '/discover/studio/shell',
    icon: 'layout-grid',
    title: 'The shell',
    sub: 'Sidebar, subsystem switcher, and an accent per subsystem.'
  },
  {
    to: '/discover/studio/search',
    icon: 'search',
    title: 'Search & quick actions',
    sub: 'One keystroke to find anything or start anything.'
  },
  {
    to: '/discover/studio/personas',
    icon: 'users',
    title: 'Studio Personas',
    sub: 'One app, shaped to each person who opens it.'
  },
  {
    to: '/discover/studio/collaboration',
    icon: 'message-circle',
    title: 'Collaboration',
    sub: 'Live co-editing, and a chat dock that follows you.'
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
      <h2>More on <em>Studio</em></h2>
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
