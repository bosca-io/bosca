<script setup lang="ts">
const { accent } = useCurrentSubsystem()
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Content Performance')"
        title="Content Performance"
        subtitle="Views, engagement, and publish rates">
        <template #actions>
          <Button size="sm" icon="calendar">Last 30 days</Button>
        </template>
      </PageHeader>
    </template>

    <div class="kpi-grid">
      <StatTile label="Total Published" value="—" :accent="accent" />
      <StatTile label="Views This Period" value="—" :accent="accent" />
      <StatTile label="Avg. Engagement" value="—" :accent="accent" />
      <StatTile label="Publish Rate" value="—" :accent="accent" />
    </div>

    <div class="chart-row">
      <Card glass title="Views Over Time">
        <div class="chart-placeholder">Configure a dashboard visualization to populate this chart.</div>
      </Card>
      <Card glass title="Top Content">
        <div class="chart-placeholder">Configure a dashboard visualization to populate this chart.</div>
      </Card>
    </div>
  </PageShell>
</template>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 18px; }
.chart-row { display: grid; grid-template-columns: 1.4fr 1fr; gap: 18px; }
.chart-placeholder { padding: 40px; text-align: center; font-size: 13px; color: var(--fg-3); }
</style>
