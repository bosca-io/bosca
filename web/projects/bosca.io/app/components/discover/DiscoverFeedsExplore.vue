<script setup lang="ts">
// The Feeds marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/feeds',
    icon: 'rss',
    title: 'Feeds overview',
    sub: 'RSS, Atom, and JSON Feed sources, ingested as content and served per reader.'
  },
  {
    to: '/discover/feeds/sources',
    icon: 'globe',
    title: 'Sources',
    sub: 'Formats, cron scheduling, conditional fetches, outbound auth, and ownership.'
  },
  {
    to: '/discover/feeds/ingestion',
    icon: 'download',
    title: 'Ingestion',
    sub: 'GUID dedup, rich-text normalization, featured images, and lifecycle events.'
  },
  {
    to: '/discover/feeds/serving',
    icon: 'inbox',
    title: 'Serving & subscriptions',
    sub: 'The Following and For-you feeds, item recommendations, and the Studio preview.'
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
      <h2>More on <em>Feeds</em></h2>
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
