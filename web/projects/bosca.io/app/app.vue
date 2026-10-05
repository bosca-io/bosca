<script setup lang="ts">
const route = useRoute()
const siteUrl = useRuntimeConfig().public.siteUrl

// The canonical URL for the current page: configured origin + path, query
// dropped, trailing slash normalized away (except at the root).
const canonicalUrl = computed(() => {
  const path = route.path === '/' ? '' : route.path.replace(/\/+$/, '')
  return `${siteUrl}${path}`
})

useHead({
  meta: [
    { charset: 'utf-8' },
    { name: 'viewport', content: 'width=device-width, initial-scale=1' },
    { key: 'theme-color', name: 'theme-color', content: '#0a0c12' }
  ],
  link: [
    { rel: 'canonical', href: canonicalUrl }
  ],
  htmlAttrs: { lang: 'en' }
})

useSeoMeta({
  titleTemplate: '%s — Bosca',
  description: 'One platform for content, audience, and delivery.',
  ogSiteName: 'Bosca',
  ogType: 'website',
  ogUrl: canonicalUrl,
  // Absolute URL to a raster image — OG scrapers ignore relative URLs and SVG.
  ogImage: `${siteUrl}/og.png`,
  twitterCard: 'summary_large_image'
})

const { init: initTheme } = useTheme()
onMounted(() => initTheme())
</script>

<template>
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
