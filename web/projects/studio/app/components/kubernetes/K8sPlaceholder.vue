<script setup lang="ts">
defineProps<{
  title: string
  sub?: string
  icon?: string
}>()

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster } = useK8sCluster()
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', title)"
        :title="title"
        :subtitle="sub"
      >
        <template #actions>
          <ClusterSwitcher :cluster="current" :clusters="clusters" @pick="setCluster" />
        </template>
      </PageHeader>
    </template>

    <div class="empty">
      <div class="icon-wrap" :style="{ color: accent, background: `color-mix(in oklab, ${accent} 14%, transparent)` }">
        <Icon :name="icon || 'container'" :size="28" />
      </div>
      <h3>{{ title }}</h3>
      <p>This Kubernetes view is scaffolded. Live data will arrive once <span class="mono">kubernetes-controller</span> and the GraphQL resolvers in <span class="mono">io.bosca:kubernetes</span> are wired up.</p>
    </div>
  </PageShell>
</template>

<style scoped>
.empty {
  padding: 60px 24px;
  display: grid;
  place-items: center;
  text-align: center;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--bg-1);
}
.icon-wrap {
  width: 56px; height: 56px;
  border-radius: 12px;
  display: grid; place-items: center;
  margin-bottom: 14px;
}
.empty h3 { margin: 0; font-size: 17px; font-weight: 600; letter-spacing: -0.01em; }
.empty p {
  max-width: 460px;
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--fg-2);
  line-height: 1.55;
}
.mono { font-family: var(--font-mono); font-size: 12px; padding: 1px 6px; border-radius: 4px; background: var(--bg-2); border: 1px solid var(--line); }
</style>
