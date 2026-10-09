<script setup lang="ts">
import { SECTIONS } from '~/composables/useSections'

const groups = SECTIONS.find(section => section.id === 'developers')?.groups ?? []
</script>

<template>
  <ReferenceShell
    title="Developer Documentation"
    subtitle="Setup and platform reference"
    home="/developers/getting-started"
    overview-to="/discover/developers"
    overview-label="Developers overview"
    :groups="groups"
  >
    <slot />
  </ReferenceShell>
</template>
