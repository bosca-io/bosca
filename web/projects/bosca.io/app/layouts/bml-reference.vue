<script setup lang="ts">
const groups = [
  {
    title: 'Start Here',
    links: [
      { label: 'Getting Started', to: '/bml-reference/getting-started' }
    ]
  },
  {
    title: 'Language',
    links: [
      { label: 'Grammar', to: '/bml-reference/grammar' },
      { label: 'Tag Reference', to: '/bml-reference/tag-reference' }
    ]
  },
  {
    title: 'Runtime',
    links: [
      { label: 'Islands', to: '/bml-reference/islands' },
      { label: 'Examples', to: '/bml-reference/examples' }
    ]
  }
]
</script>

<template>
  <ReferenceShell
    title="BML Reference"
    subtitle="Language and runtime"
    home="/bml-reference/getting-started"
    overview-to="/discover/bml"
    overview-label="BML overview"
    source-url="https://github.com/bosca-io/bosca"
    :groups="groups"
  >
    <slot />
  </ReferenceShell>
</template>
