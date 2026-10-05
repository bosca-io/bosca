<script setup lang="ts">
// The CMS marketing page family. Rendered as link cards on every page in the
// family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/cms',
    icon: 'library',
    title: 'CMS overview',
    sub: 'What the content engine is, and how a piece of content moves through it.'
  },
  {
    to: '/discover/cms/library',
    icon: 'layers',
    title: 'The content model',
    sub: 'Collections, metadata, and the typed items that fill your library.'
  },
  {
    to: '/discover/cms/authoring',
    icon: 'edit-3',
    title: 'Authoring',
    sub: 'Real-time collaborative documents, guides, data forms, and templates.'
  },
  {
    to: '/discover/cms/media',
    icon: 'video',
    title: 'Media',
    sub: 'Adaptive streaming, quality tiers, previews, and auto-transcription.'
  },
  {
    to: '/discover/cms/publishing',
    icon: 'workflow',
    title: 'Workflow & publishing',
    sub: 'States, scheduled publishing, visibility, health, and moderation.'
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
      <h2>More on the <em>CMS</em></h2>
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
