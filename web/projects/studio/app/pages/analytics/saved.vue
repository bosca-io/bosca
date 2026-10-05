<script setup lang="ts">
const { accent } = useCurrentSubsystem()
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Saved Reports')"
        title="Saved Reports"
        subtitle="Your saved report configurations">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent">New Report</Button>
        </template>
      </PageHeader>
    </template>
    <div class="empty-state">
      <Icon name="file" :size="32" color="var(--fg-4)" />
      <span>No saved reports yet. Create one from the Explorer.</span>
    </div>
  </PageShell>
</template>

<style scoped>
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 64px; font-size: 13px; color: var(--fg-3); }
</style>
