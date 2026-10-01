<script setup lang="ts">
const { accent } = useCurrentSubsystem()
</script>
<template>
  <PageShell>
    <template #header><PageHeader
      :accent="accent"
      :breadcrumb="buildBreadcrumb('Forms', 'Anti-spam')"
      title="Anti-spam"
      subtitle="Spam detection and prevention" /></template>
    <div style="display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 64px; font-size: 13px; color: var(--fg-3)">
      <Icon name="shield" :size="32" color="var(--fg-4)" />
      <span>Configure spam detection sensitivity, allowlists, and blocklists.</span>
    </div>
  </PageShell>
</template>
