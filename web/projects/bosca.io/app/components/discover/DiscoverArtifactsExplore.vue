<script setup lang="ts">
// The Artifacts marketing page family. Rendered as link cards on every page in
// the family; the current route is excluded automatically so each page offers
// only its siblings.
const PAGES = [
  {
    to: '/discover/artifacts',
    icon: 'package',
    title: 'Artifacts overview',
    sub: 'Six registry formats behind one namespace and permission model.'
  },
  {
    to: '/discover/artifacts/registries',
    icon: 'container',
    title: 'Registries',
    sub: 'Docker, Helm, Maven, npm, ML models, and raw files — native protocols.'
  },
  {
    to: '/discover/artifacts/storage',
    icon: 'database',
    title: 'Storage',
    sub: 'Content-addressed blobs, digest verification, reference-counted cleanup.'
  },
  {
    to: '/discover/artifacts/access',
    icon: 'key',
    title: 'Access control',
    sub: 'Public or private namespaces, group grants, and scoped registry tokens.'
  },
  {
    to: '/discover/artifacts/integration',
    icon: 'share-2',
    title: 'Platform integration',
    sub: 'Publish events, CI requirement gates, ML model serving, and admin APIs.'
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
      <h2>More on <em>Artifacts</em></h2>
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
