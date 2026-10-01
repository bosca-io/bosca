<script setup lang="ts">
// The Localization marketing page family. Rendered as link cards on every page
// in the family; the current route is excluded automatically so each page
// offers only its siblings.
const PAGES = [
  {
    to: '/discover/localization',
    icon: 'languages',
    title: 'Localization overview',
    sub: 'Strings and documents, translated with a real review workflow.'
  },
  {
    to: '/discover/localization/strings',
    icon: 'type',
    title: 'Strings & Translations',
    sub: 'Keys with context, ICU placeholders, and CLDR-correct plurals.'
  },
  {
    to: '/discover/localization/documents',
    icon: 'file-text',
    title: 'Documents',
    sub: 'Localize whole CMS documents, per language, with HTML round-trip.'
  },
  {
    to: '/discover/localization/workflow',
    icon: 'workflow',
    title: 'Workflow',
    sub: 'Draft to published, with origin tracking and an audit trail.'
  },
  {
    to: '/discover/localization/export',
    icon: 'download',
    title: 'Export',
    sub: 'Eight formats — Android, iOS, Flutter, web, and XLIFF.'
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
      <h2>More on <em>Localization</em></h2>
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
