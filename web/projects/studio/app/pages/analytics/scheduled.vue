<script setup lang="ts">
const { accent } = useCurrentSubsystem()
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Scheduled Reports')"
        title="Scheduled Reports"
        subtitle="Automated report delivery">
        <template #actions>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent">Schedule Report</Button>
        </template>
      </PageHeader>
    </template>
    <div class="empty-state">
      <Icon name="calendar" :size="32" color="var(--fg-4)" />
      <span>No scheduled reports. Create a report and set up delivery.</span>
    </div>
  </PageShell>
</template>

<style scoped>
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 64px; font-size: 13px; color: var(--fg-3); }
</style>
