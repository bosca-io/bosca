<script setup lang="ts">
import { DashboardDisplay } from '@bosca/ui-analytics'

const route = useRoute()
const { accent } = useCurrentSubsystem()
const { $auth } = useNuxtApp()

const { dashboardNavGroups, dashboardsLoading, dashboardsEmpty } = useDashboardNav()
const firstKey = computed(() => dashboardNavGroups.value?.[0]?.items?.[0]?.id?.split('key=')?.[1] ?? '')
const dashboardKey = computed(() => (route.query.key as string) || firstKey.value)

// Auto-select first dashboard when none is specified in the URL
watch(firstKey, (key) => {
  if (key && !route.query.key) {
    navigateTo({ path: route.path, query: { key } }, { replace: true })
  }
}, { immediate: true })

async function getHeaders() {
  return $auth ? await $auth.getAuthHeaders() : {}
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Analytics', 'Dashboard')"
        title="Dashboard"
      />
    </template>

    <div v-if="dashboardsLoading" class="empty-state">
      <span class="loading-text">Loading dashboards&hellip;</span>
    </div>
    <div v-else-if="dashboardsEmpty" class="empty-state">
      <span class="empty-title">No dashboards yet</span>
      <span class="empty-detail">An administrator can create dashboards in Analytics &rsaquo; Dashboards.</span>
    </div>
    <div v-else-if="!dashboardKey" class="empty-state">Select a dashboard from the sidebar.</div>
    <DashboardDisplay v-else :dashboard-key="dashboardKey" :get-headers="getHeaders">
      <!-- LIVE_SESSIONS_MAP has no query — DashboardDisplay delegates it here so the
           subscription-fed renderer (with Studio's ws + auth layer) can stream it. -->
      <template #live-sessions="{ title, configuration }">
        <LiveSessionsVisualization
          :name="title"
          :configuration="configuration"
          frameless />
      </template>
    </DashboardDisplay>
  </PageShell>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 200px;
  font-size: 14px;
  color: var(--fg-3);
}

.loading-text {
  color: var(--fg-3);
}

.empty-title {
  font-weight: 500;
  color: var(--fg-2);
}

.empty-detail {
  font-size: 13px;
  color: var(--fg-3);
}
</style>
