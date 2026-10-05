<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'
import type { RecommendationContext } from '~/types/graphql'

definePageMeta({ middleware: 'recommendations-admin' })

const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation } = useGraphQL()
const router = useRouter()
const toast = useToast()
const recomputing = ref(false)
const recomputeGql = gql`
  mutation RecomputeRecommendationContextAssignments {
    recommendation { contexts { recompute } }
  }
`

async function recomputeAssignments() {
  if (recomputing.value) return
  recomputing.value = true
  try {
    await mutation(recomputeGql)
    toast.success('Content assignment recomputation queued for all contexts.')
  } catch (recomputeError: unknown) {
    toast.error(recomputeError instanceof Error ? recomputeError.message : 'Failed to recompute content assignments')
  } finally {
    recomputing.value = false
  }
}

const listGql = gql`
  query RecommendationContexts {
    recommendation {
      contexts {
        all {
          id
          type
          name
          modified
          contentFilter {
            metadata {
              includedContentTypePrefixes
              excludedContentTypePrefixes
              includedAttributeTypes
              excludedAttributeTypes
            }
            collections {
              includedTypes
              excludedTypes
              includedAttributeTypes
              excludedAttributeTypes
            }
          }
        }
      }
    }
  }
`

type ContextRow = Pick<RecommendationContext, 'id' | 'type' | 'name' | 'modified' | 'contentFilter'>

interface ContextsData {
  recommendation: { contexts: { all: ContextRow[] } }
}

const { data, status, error } = useAsyncQuery<ContextsData>('recommendation-contexts', listGql)
const contexts = computed(() => data.value?.recommendation?.contexts?.all ?? [])

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Context', width: 'minmax(200px, 2fr)' },
  { key: 'type', label: 'Request type', width: '180px', muted: true },
  { key: 'metadata', label: 'Metadata rules', width: '1fr', muted: true },
  { key: 'collections', label: 'Collections', width: '1fr', muted: true },
  { key: 'modified', label: 'Modified', width: '180px', muted: true },
]

function metadataSummary(context: ContextRow): string {
  const filter = context.contentFilter.metadata
  return ruleSummary([
    [filter.includedContentTypePrefixes.length, filter.excludedContentTypePrefixes.length],
    [filter.includedAttributeTypes.length, filter.excludedAttributeTypes.length],
  ])
}

function collectionSummary(context: ContextRow): string {
  const filter = context.contentFilter.collections
  if (!filter) return 'Excluded'
  return ruleSummary([
    [filter.includedTypes.length, filter.excludedTypes.length],
    [filter.includedAttributeTypes.length, filter.excludedAttributeTypes.length],
  ])
}

function ruleSummary(facets: Array<[included: number, excluded: number]>): string {
  const included = facets.reduce((total, [count]) => total + count, 0)
  const excluded = facets.reduce((total, [allowCount, denyCount]) => total + (allowCount ? 0 : denyCount), 0)
  if (!included && !excluded) return 'All'
  const parts = []
  if (included) parts.push(`${included} included`)
  if (excluded) parts.push(`${excluded} excluded`)
  return parts.join(', ')
}

function fmtDate(value: string): string {
  return new Date(value).toLocaleString()
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Recommendations', 'Contexts')"
        title="Recommendation Contexts"
        subtitle="Control which content is eligible for each recommendation experience">
        <template #actions>
          <Button
            icon="refresh"
            size="sm"
            :loading="recomputing"
            :disabled="recomputing"
            @click="recomputeAssignments">Recompute assignments</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="router.push('/recommendations/contexts/new')">New Context</Button>
        </template>
      </PageHeader>
    </template>

    <div class="backfill-note">
      After changing or deleting contexts, use Recompute assignments to update metadata and collection
      assignments across all contexts. Use Train model on a context to start training with its saved settings.
    </div>

    <div v-if="error" class="query-error">Couldn't load recommendation contexts — {{ error.message }}</div>

    <GlassTable
      :columns="columns"
      :rows="contexts"
      row-key="id"
      :loading="status === 'pending'"
      empty-text="No recommendation contexts have been configured."
      arrow
      @row-click="(row) => router.push(`/recommendations/contexts/${row.id}`)">
      <template #col-name="{ row }">
        <span class="context-name">{{ row.name }}</span>
        <Badge v-if="row.type === 'default'" :color="accent">Default</Badge>
      </template>
      <template #col-type="{ row }"><code class="mono">{{ row.type }}</code></template>
      <template #col-metadata="{ row }">{{ metadataSummary(row) }}</template>
      <template #col-collections="{ row }">{{ collectionSummary(row) }}</template>
      <template #col-modified="{ row }">{{ fmtDate(row.modified) }}</template>
    </GlassTable>
  </PageShell>
</template>

<style scoped>
.backfill-note,
.query-error {
  padding: 9px 11px;
  margin-bottom: 12px;
  border-radius: 4px;
  font-size: 12.5px;
  color: var(--fg-2);
}

.backfill-note {
  background: var(--bg-2);
  border: 1px solid var(--line);
}

.query-error {
  background: var(--bg-3);
  border-left: 3px solid var(--err, #ff5c5c);
}

.context-name {
  margin-right: 8px;
}

.mono {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 12px;
}
</style>
