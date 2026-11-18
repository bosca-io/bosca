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
    title: 'Compiled Libraries',
    links: [
      { label: 'Setup & Imports', to: '/bml-reference/libraries' },
      { label: 'Providers', to: '/bml-reference/library-providers' },
      { label: 'Localization', to: '/bml-reference/library-localization' },
      { label: 'Client Event Types', to: '/bml-reference/library-client-types' }
    ]
  },
  {
    title: 'Design Library',
    links: [
      { label: 'Components', to: '/bml-reference/design-library' },
      { label: 'CSS Variables', to: '/bml-reference/design-tokens' },
      { label: 'Git UI Migration', to: '/bml-reference/design-migration' }
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
