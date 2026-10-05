<script setup lang="ts">
const { accent } = useCurrentSubsystem()
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Audience')"
        title="Audience Analytics"
        subtitle="Profile growth, segments, and campaign performance">
        <template #actions>
          <Button size="sm" icon="calendar">Last 30 days</Button>
        </template>
      </PageHeader>
    </template>

    <div class="kpi-grid">
      <StatTile label="Total Profiles" value="—" :accent="accent" />
      <StatTile label="New This Period" value="—" :accent="accent" />
      <StatTile label="Active Segments" value="—" :accent="accent" />
      <StatTile label="Campaign Opens" value="—" :accent="accent" />
    </div>

    <div class="chart-row">
      <Card glass title="Growth Over Time">
        <div class="chart-placeholder">Configure analytics queries to populate.</div>
      </Card>
      <Card glass title="Segment Distribution">
        <div class="chart-placeholder">Configure analytics queries to populate.</div>
      </Card>
    </div>
  </PageShell>
</template>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 18px; }
.chart-row { display: grid; grid-template-columns: 1.4fr 1fr; gap: 18px; }
.chart-placeholder { padding: 40px; text-align: center; font-size: 13px; color: var(--fg-3); }
</style>
