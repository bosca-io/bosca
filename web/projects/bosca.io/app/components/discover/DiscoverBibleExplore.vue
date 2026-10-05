<script setup lang="ts">
// The Bible marketing page family. Rendered as link cards on every page in the
// family; the current route is excluded automatically so each page offers only
// its siblings.
const PAGES = [
  {
    to: '/discover/bible',
    icon: 'book',
    title: 'Bible overview',
    sub: 'Scripture as structured, addressable, multilingual content.'
  },
  {
    to: '/discover/bible/translations',
    icon: 'languages',
    title: 'Translations',
    sub: 'Many translations, language-aware, imported from Paratext and USX.'
  },
  {
    to: '/discover/bible/books',
    icon: 'book-open',
    title: 'Books & Chapters',
    sub: 'Books, chapters, and verses as a rich, addressable tree.'
  },
  {
    to: '/discover/bible/references',
    icon: 'quote',
    title: 'References',
    sub: 'Parse a plain reference into canonical USFM and pull the verses.'
  },
  {
    to: '/discover/bible/ai',
    icon: 'sparkles',
    title: 'AI Tools',
    sub: 'Agents that quote the real translation, never an invented verse.'
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
      <h2>More on <em>Bible</em></h2>
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
