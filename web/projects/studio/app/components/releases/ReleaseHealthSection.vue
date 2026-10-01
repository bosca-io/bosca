<script setup lang="ts">
import gql from 'graphql-tag'
import ReleaseAppHealthRow from '~/components/releases/ReleaseAppHealthRow.vue'
import ReleaseServiceHealthRow from '~/components/releases/ReleaseServiceHealthRow.vue'
import ReleaseStoreHealthSection from '~/components/releases/ReleaseStoreHealthSection.vue'

/**
 * Release health telemetry: every analytics application and service across the
 * release's projects, at a high level. Each project declares its analytics apps (`sessions.<appId>` /
 * error groups) and services (`http.<service>` response codes) on its Analytics tab; this gathers them
 * for the release and renders a live health row per app and per service.
 */

const props = defineProps<{ releaseId: string }>()

const { useAsyncQuery } = useGraphQL()

interface ProjectVersion {
  projectId: string
  project: {
    key: string
    name: string
    analyticsApplications: Array<{ applicationId: string }>
    analyticsServices: Array<{ service: string }>
  } | null
}

const projectsGql = gql`
  query ReleaseHealthProjects($releaseId: UUID!) {
    workOps { crossProject { versionsForRelease(releaseId: $releaseId) {
      projectId
      project { key name analyticsApplications { applicationId } analyticsServices { service } }
    } } }
  }
`
const { data, status } = useAsyncQuery<{
  workOps: { crossProject: { versionsForRelease: ProjectVersion[] } }
}>('release-health-projects', projectsGql, { releaseId: computed(() => props.releaseId || undefined) }, { server: false })

const projectVersions = computed(() => data.value?.workOps?.crossProject?.versionsForRelease ?? [])

// Distinct apps/services across the release's projects (first project that declares each labels it).
const apps = computed(() => {
  const seen = new Map<string, string>()
  for (const pv of projectVersions.value) {
    const key = pv.project?.key ?? '—'
    for (const a of pv.project?.analyticsApplications ?? []) if (!seen.has(a.applicationId)) seen.set(a.applicationId, key)
  }
  return [...seen.entries()].map(([applicationId, projectKey]) => ({ applicationId, projectKey }))
})
const services = computed(() => {
  const seen = new Map<string, string>()
  for (const pv of projectVersions.value) {
    const key = pv.project?.key ?? '—'
    for (const s of pv.project?.analyticsServices ?? []) if (!seen.has(s.service)) seen.set(s.service, key)
  }
  return [...seen.entries()].map(([service, projectKey]) => ({ service, projectKey }))
})
const nothing = computed(() => !apps.value.length && !services.value.length)
</script>

<template>
  <div class="rail-card">
    <p class="rail-card-title">Health</p>
    <div v-if="status === 'pending' && !data" class="tele-state">Loading…</div>
    <div v-else-if="nothing" class="tele-state">
      No analytics applications or services are configured on this release's projects. Add them on each
      project's <strong>Analytics</strong> tab, and they'll show up here.
    </div>

    <div v-else class="tele-rows">
      <ReleaseAppHealthRow
        v-for="a in apps"
        :key="a.applicationId"
        :application-id="a.applicationId"
        :project-key="a.projectKey" />
      <ReleaseServiceHealthRow
        v-for="s in services"
        :key="s.service"
        :service="s.service"
        :project-key="s.projectKey" />
      <ReleaseStoreHealthSection :release-id="releaseId" />
    </div>
    <ReleaseStoreHealthSection v-if="nothing" :release-id="releaseId" />
  </div>
</template>

<style scoped>
.tele-state { font-size: 12.5px; color: var(--fg-3); line-height: 1.5; }
.tele-state strong { color: var(--fg-1); font-weight: 600; }
.rail-card { padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--bg-1); }
.rail-card-title { margin: 0 0 6px; font-size: 11px; text-transform: uppercase; letter-spacing: 0.08em; font-weight: 600; color: var(--fg-3); }
.tele-rows { display: flex; flex-direction: column; }
</style>
