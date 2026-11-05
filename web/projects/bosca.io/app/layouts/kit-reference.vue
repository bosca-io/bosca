<script setup lang="ts">
import { KIT_DOC_GROUPS } from '~/data/kit-reference'
</script>

<template>
  <ReferenceShell
    title="Kit Reference"
    subtitle="Conversations, deployment, and operations"
    home="/kit-reference/getting-started"
    overview-to="/discover/ai/kit"
    overview-label="Kit overview"
    :groups="KIT_DOC_GROUPS"
  >
    <slot />
  </ReferenceShell>
</template>
