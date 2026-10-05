<script setup lang="ts">
const { accent } = useCurrentSubsystem()
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Work Ops')"
        title="Work Ops Analytics"
        subtitle="Task throughput, cycle time, and SLA compliance">
        <template #actions>
          <Button size="sm" icon="calendar">Last 30 days</Button>
        </template>
      </PageHeader>
    </template>

    <div class="kpi-grid">
      <StatTile label="Tasks Completed" value="—" :accent="accent" />
      <StatTile label="Avg Cycle Time" value="—" :accent="accent" />
      <StatTile label="Open Tasks" value="—" :accent="accent" />
      <StatTile label="SLA Compliance" value="—" :accent="accent" />
    </div>

    <div class="chart-row">
      <Card glass title="Throughput">
        <div class="chart-placeholder">Configure analytics queries to populate.</div>
      </Card>
      <Card glass title="Status Distribution">
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
