<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

// Library composition comes from the search index; `type` is a real facet
// (same one the documents listing uses).
const facetsGql = gql`
  query GetHealthFacets {
    search {
      search(query: {
        query: ""
        filter: ["_type = \\"metadata\\""]
        storageSystemName: "Admin Search Index"
        facets: ["type"]
      }) {
        estimatedHits
        facets { field value count }
      }
    }
  }
`

const healthGql = gql`
  query GetContentHealthCheck {
    content {
      healthCheck {
        publishedWithUnpublishedRelationships { id name workflowState }
        scheduledButNotPublished { id name workflowState }
        pendingNotReady { id name workflowState }
        guidesWithUnpublishedSteps { id name workflowState }
        publishedCollectionsWithUnpublishedMetadata { id name workflowState }
        failedJobItems { id name workflowState }
        deletedItems { count }
        missingContent { id name workflowState }
      }
    }
  }
`

const resolveRelationshipsGql = gql`
  mutation ResolveUnpublishedRelationships($id: UUID!) {
    content { healthCheck { resolveUnpublishedRelationships(id: $id) } }
  }
`

interface HealthItem { id: string; name: string; workflowState: string }

interface HealthCheckData {
  publishedWithUnpublishedRelationships: HealthItem[]
  scheduledButNotPublished: HealthItem[]
  pendingNotReady: HealthItem[]
  guidesWithUnpublishedSteps: HealthItem[]
  publishedCollectionsWithUnpublishedMetadata: HealthItem[]
  failedJobItems: HealthItem[]
  deletedItems: { count: number }
  missingContent: HealthItem[]
}

const { data: facetsData } = useAsyncQuery<{
  search: { search: { estimatedHits: number; facets: Array<{ field: string; value: string; count: number }> } }
}>('health-facets', facetsGql, {})

const { data: healthData, status: healthStatus, refresh: refreshHealth } = useAsyncQuery<{
  content: { healthCheck: HealthCheckData | null }
}>('health-issues', healthGql, {})

const totalItems = computed(() => facetsData.value?.search?.search?.estimatedHits ?? 0)
const facets = computed(() => facetsData.value?.search?.search?.facets ?? [])

const TYPE_COLORS: Record<string, string> = {
  document: '#5ec5ff', video: '#ff7ac6', image: '#a78bff',
  audio: '#ffb547', guide: '#34d99a', data: '#5ec5ff', bible: '#7c5cff',
}

const types = computed(() => {
  return facets.value
    .filter(f => f.field === 'type')
    .sort((a, b) => b.count - a.count)
    .map((f) => {
      const name = f.value.split('/').pop()?.replace(/^v-/, '').replace(/-/g, ' ') ?? f.value
      return { name: name.charAt(0).toUpperCase() + name.slice(1), n: f.count, pct: totalItems.value ? Math.round(f.count / totalItems.value * 100) : 0, color: TYPE_COLORS[name.toLowerCase()] ?? accent.value }
    })
})

const health = computed(() => healthData.value?.content?.healthCheck ?? null)

interface HealthSection {
  key: string
  title: string
  description: string
  severity: 'err' | 'warn' | 'info'
  items: HealthItem[]
  /** Issue items link to this editor. */
  linkPrefix: string
  resolvable?: boolean
}

const sections = computed<HealthSection[]>(() => {
  const hc = health.value
  if (!hc) return []
  return [
    {
      key: 'failed-jobs',
      title: 'Failed Jobs',
      description: 'Content items with jobs that failed and need investigation.',
      severity: 'err',
      items: hc.failedJobItems ?? [],
      linkPrefix: '/cms/metadata/',
    },
    {
      key: 'unpublished-relationships',
      title: 'Published with Unpublished Relationships',
      description: 'Published content that references unpublished items, which may cause broken links or missing content for users.',
      severity: 'warn',
      items: hc.publishedWithUnpublishedRelationships ?? [],
      linkPrefix: '/cms/metadata/',
      resolvable: true,
    },
    {
      key: 'scheduled-not-published',
      title: 'Scheduled but Not Published',
      description: 'Content with a past scheduled publish date that has not transitioned to published state.',
      severity: 'warn',
      items: hc.scheduledButNotPublished ?? [],
      linkPrefix: '/cms/metadata/',
    },
    {
      key: 'pending-not-ready',
      title: 'Pending & Not Ready',
      description: 'Content in a pending state that has not been marked as ready by an editor.',
      severity: 'info',
      items: hc.pendingNotReady ?? [],
      linkPrefix: '/cms/metadata/',
    },
    {
      key: 'guides-unpublished-steps',
      title: 'Guides with Unpublished Steps',
      description: 'Published guides containing steps that reference unpublished content.',
      severity: 'warn',
      items: hc.guidesWithUnpublishedSteps ?? [],
      linkPrefix: '/cms/metadata/',
    },
    {
      key: 'collections-unpublished-items',
      title: 'Published Collections with Unpublished Content',
      description: 'Published collections containing child items that are not yet published.',
      severity: 'warn',
      items: hc.publishedCollectionsWithUnpublishedMetadata ?? [],
      linkPrefix: '/cms/collections/',
    },
    {
      key: 'missing-content',
      title: 'Missing Content',
      description: 'Non-draft items with no uploaded content, document, guide, or data associated.',
      severity: 'info',
      items: hc.missingContent ?? [],
      linkPrefix: '/cms/metadata/',
    },
  ]
})

const totalIssues = computed(() => sections.value.reduce((sum, s) => sum + s.items.length, 0))
const failedJobCount = computed(() => health.value?.failedJobItems?.length ?? 0)
const deletedCount = computed(() => health.value?.deletedItems?.count ?? 0)

const resolvingIds = ref<Set<string>>(new Set())

async function resolveItem(item: HealthItem) {
  resolvingIds.value = new Set([...resolvingIds.value, item.id])
  try {
    await gqlMutation(resolveRelationshipsGql, { id: item.id })
    toast.success(`Made related items public for "${item.name}"`)
    await refreshHealth()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to resolve')
  } finally {
    const ids = new Set(resolvingIds.value)
    ids.delete(item.id)
    resolvingIds.value = ids
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Content Health')"
        title="Content Health"
        subtitle="Library quality and issues that need attention"
      >
        <template #actions>
          <Button
            size="sm"
            icon="refresh"
            :disabled="healthStatus === 'pending'"
            @click="refreshHealth()">
            {{ healthStatus === 'pending' ? 'Checking…' : 'Refresh' }}
          </Button>
        </template>
      </PageHeader>
    </template>

    <StatGrid>
      <StatTile label="Items" :value="totalItems.toLocaleString()" :accent="accent" />
      <StatTile label="Total issues" :value="String(totalIssues)" :accent="totalIssues > 0 ? 'var(--warn)' : 'var(--ok)'" />
      <StatTile label="Failed jobs" :value="String(failedJobCount)" :accent="failedJobCount > 0 ? 'var(--err)' : 'var(--ok)'" />
      <StatTile label="Deleted items" :value="String(deletedCount)" :accent="accent" />
    </StatGrid>

    <SectionCard title="Library composition" glass>
      <template #right>
        <span class="mono section-count">{{ totalItems.toLocaleString() }} items</span>
      </template>
      <div class="composition-body">
        <div v-if="types.length" class="bar-chart">
          <div v-for="t in types" :key="t.name" :style="{ flex: t.pct || 1, background: t.color }" />
        </div>
        <div v-if="types.length" class="type-grid">
          <div v-for="t in types" :key="t.name" class="type-col">
            <div class="type-label-row">
              <span :style="{ width: '7px', height: '7px', borderRadius: '2px', background: t.color }" />
              <span class="type-name">{{ t.name }}</span>
            </div>
            <span class="mono tabular type-value">{{ t.n.toLocaleString() }}</span>
            <span class="type-pct">{{ t.pct }}%</span>
          </div>
        </div>
        <div v-else class="empty-note">Loading…</div>
      </div>
    </SectionCard>

    <template v-for="section in sections" :key="section.key">
      <SectionCard v-if="section.items.length" :title="section.title" glass>
        <template #right>
          <span class="mono section-count">{{ section.items.length }}</span>
        </template>
        <div class="section-body">
          <p class="section-desc">{{ section.description }}</p>
          <div
            v-for="(item, i) in section.items"
            :key="item.id"
            class="issue-row"
            :style="{ borderBottom: i < section.items.length - 1 ? '1px solid var(--line)' : 'none' }"
          >
            <span :class="`dot ${section.severity}`" />
            <NuxtLink :to="`${section.linkPrefix}${item.id}`" class="issue-name">{{ item.name }}</NuxtLink>
            <Badge color="var(--fg-4)">{{ item.workflowState }}</Badge>
            <Button
              v-if="section.resolvable"
              size="sm"
              icon="check"
              :disabled="resolvingIds.has(item.id)"
              @click="resolveItem(item)">
              {{ resolvingIds.has(item.id) ? 'Resolving…' : 'Resolve' }}
            </Button>
            <Button size="sm" icon="external-link" @click="navigateTo(`${section.linkPrefix}${item.id}`)">Open</Button>
          </div>
        </div>
      </SectionCard>
    </template>

    <SectionCard v-if="health && totalIssues === 0" title="Issues" glass>
      <div class="healthy">
        <Icon
          name="check"
          :size="20"
          color="var(--ok)" />
        <div class="healthy-title">All content looks healthy</div>
        <div class="healthy-sub">No issues were found in your content.</div>
      </div>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.section-count { font-size: 11px; color: var(--fg-3); }

.composition-body { padding: 18px; }
.bar-chart { display: flex; height: 10px; border-radius: 5px; overflow: hidden; gap: 1px; }
.type-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 10px; margin-top: 14px; }
.type-col { display: flex; flex-direction: column; gap: 2px; }
.type-label-row { display: flex; align-items: center; gap: 6px; }
.type-name { font-size: 11.5px; color: var(--fg-2); }
.type-value { font-size: 16px; font-weight: 600; color: var(--fg-0); }
.type-pct { font-size: 11px; color: var(--fg-3); }
.empty-note { padding: 20px; text-align: center; color: var(--fg-3); font-size: 13px; }

.section-body { padding: 4px 16px 8px; }
.section-desc { font-size: 12px; color: var(--fg-3); margin: 10px 0; }

.issue-row { display: flex; align-items: center; gap: 12px; padding: 10px 0; }
.issue-name { flex: 1; min-width: 0; font-size: 13px; color: var(--fg-0); text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.issue-name:hover { color: var(--brand-2); }

.dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.dot.err { background: var(--err); }
.dot.warn { background: var(--warn); }
.dot.info { background: var(--brand-2); }

.healthy { padding: 36px; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 6px; }
.healthy-title { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.healthy-sub { font-size: 12.5px; color: var(--fg-3); }
</style>
