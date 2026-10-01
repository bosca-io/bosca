<script setup lang="ts">
// The Calendar marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/calendar',
    icon: 'calendar',
    title: 'Calendar overview',
    sub: 'Calendars, events, repeating series, and platform overlays.'
  },
  {
    to: '/discover/calendar/events',
    icon: 'users',
    title: 'Events & participants',
    sub: 'Event fields, RSVPs with roles and statuses, and content attachments.'
  },
  {
    to: '/discover/calendar/recurrence',
    icon: 'repeat',
    title: 'Recurrence',
    sub: 'Repeating events, one-off exceptions, series splits, and clean endings.'
  },
  {
    to: '/discover/calendar/overlays',
    icon: 'layers',
    title: 'Platform overlays',
    sub: 'Campaigns, scheduled jobs, and content publishes on the same grid.'
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
      <h2>More on <em>Calendar</em></h2>
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
