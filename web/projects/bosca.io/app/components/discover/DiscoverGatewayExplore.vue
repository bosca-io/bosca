<script setup lang="ts">
// The Gateway marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/gateway',
    icon: 'globe',
    title: 'Gateway overview',
    sub: 'Your services, behind one authenticated front door.'
  },
  {
    to: '/discover/gateway/upstreams',
    icon: 'server',
    title: 'Upstreams',
    sub: 'Register a service, watch its health, tune its connection.'
  },
  {
    to: '/discover/gateway/routes',
    icon: 'route',
    title: 'Routes',
    sub: 'Path and host patterns that decide where requests go.'
  },
  {
    to: '/discover/gateway/access',
    icon: 'lock',
    title: 'Access',
    sub: 'Sign-in methods, group rules, and forwarded identity.'
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
      <h2>More on <em>Gateway</em></h2>
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
