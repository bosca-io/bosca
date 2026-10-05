<script setup lang="ts">
const { accent } = useCurrentSubsystem()

const info = `
The suppression list contains email addresses that have been permanently excluded from
future sends due to hard bounces. Addresses are added automatically when SendGrid reports
a hard bounce event via webhook. Emails on this list will be silently skipped during delivery.
`
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Messaging', 'Suppression List')"
        title="Suppression List"
        subtitle="Hard-bounced addresses excluded from future sends"
      />
    </template>

    <SectionCard title="About" padded>
      <p class="info-text">{{ info.trim() }}</p>
      <p class="info-text muted">
        Suppression list management (search, manual add/remove) will be available in a future update.
        Currently, addresses are added automatically via SendGrid webhook hard bounce events.
      </p>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.info-text {
  font-size: 13px; line-height: 1.6; color: var(--fg-1); padding: 4px 0; max-width: 640px;
}
.info-text.muted { color: var(--fg-3); font-size: 12px; }
</style>
