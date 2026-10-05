<script setup lang="ts">
// The BML marketing page family. Rendered as link cards on every page in the
// family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/bml',
    icon: 'braces',
    title: 'BML overview',
    sub: 'What BML is, what makes it powerful, and how to use it.'
  },
  {
    to: '/discover/bml/language',
    icon: 'code',
    title: 'The language',
    sub: 'Pages, components, control flow, and scoped styles.'
  },
  {
    to: '/discover/bml/live-state',
    icon: 'zap',
    title: 'Live state & actions',
    sub: 'Islands and interactivity without hand-written client code.'
  },
  {
    to: '/discover/bml/data',
    icon: 'database',
    title: 'Data & auth',
    sub: 'The typed GraphQL data plane and client-managed auth.'
  },
  {
    to: '/discover/bml/email-and-localization',
    icon: 'mail',
    title: 'Email & localization',
    sub: 'Localized sites and versioned, email-safe transactional templates.'
  },
  {
    to: '/discover/bml/tooling',
    icon: 'wrench',
    title: 'Tooling & deployment',
    sub: 'The compiler, the IDE plugin, the dev loop, and one-process deploys.'
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
      <h2>More on <em>BML</em></h2>
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
