<script setup lang="ts">
// The Communications marketing page family. Rendered as link cards on every
// page in the family; the current route is excluded automatically so each page
// offers only its siblings.
const PAGES = [
  {
    to: '/discover/communications',
    icon: 'message',
    title: 'Communications overview',
    sub: 'Real-time chat and reliable email and push, in one place.'
  },
  {
    to: '/discover/communications/channels',
    icon: 'message-circle',
    title: 'Channels',
    sub: 'Real-time chat channels with threads, reactions, and presence.'
  },
  {
    to: '/discover/communications/templates',
    icon: 'file-text',
    title: 'Event Templates',
    sub: 'Event keys render to email templates at send time.'
  },
  {
    to: '/discover/communications/delivery',
    icon: 'route',
    title: 'Delivery Status',
    sub: 'Know whether each message was delivered, deferred, or bounced.'
  },
  {
    to: '/discover/communications/preferences',
    icon: 'sliders',
    title: 'Preferences & Suppression',
    sub: 'Per-channel opt-outs, quiet hours, and automatic suppression.'
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
      <h2>More on <em>Communications</em></h2>
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
