<script setup lang="ts">
import gql from 'graphql-tag'
import type { GlassTableColumn } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { useAsyncQuery } = useGraphQL()
const router = useRouter()

// The landing lists only content that HAS comments, most-recent-comment first — entirely from the
// server's content.commentedMetadata query (no client sorting/filtering).
const offset = ref(0)
const limit = ref(20)

const listGql = gql`
  query CommentedMetadata($offset: Int!, $limit: Int!) {
    content {
      commentedMetadata(offset: $offset, limit: $limit) {
        id
        name
        content { type }
        hasUnmoderatedComments
        lastCommentAt
      }
    }
  }
`

interface Row {
  id: string
  name: string
  content: { type: string } | null
  hasUnmoderatedComments: boolean
  lastCommentAt: string | null
}

const { data, status } = useAsyncQuery<{ content: { commentedMetadata: Row[] } }>(
  'commented-metadata', listGql, { offset, limit },
)

const items = computed<Row[]>(() => data.value?.content?.commentedMetadata ?? [])
const isLoading = computed(() => status.value === 'pending' && items.value.length === 0)
const hasNext = computed(() => items.value.length === limit.value)
const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)

function nextPage() { if (hasNext.value) offset.value += limit.value }
function prevPage() { if (offset.value > 0) offset.value = Math.max(0, offset.value - limit.value) }

function contentLabel(m: Row): string {
  const ct = m.content?.type
  if (!ct) return '--'
  const last = ct.split('/').pop() ?? ''
  return last.replace(/^v-/, '').replace(/-/g, ' ')
}

function relativeTime(iso: string | null): string {
  if (!iso) return '--'
  const mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  const days = Math.round(hrs / 24)
  if (days < 30) return `${days}d ago`
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Content', width: 'minmax(220px, 2fr)' },
  { key: 'type', label: 'Type', width: '160px', muted: true },
  { key: 'last', label: 'Last comment', width: '140px', muted: true },
  { key: 'moderation', label: 'Comments', width: '140px' },
]
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        title="Comments"
        subtitle="Content with comments, most recent first"
        :breadcrumb="buildBreadcrumb('CMS', 'Comments')"
        :accent="accent"
      />
    </template>

    <SectionCard title="Content with comments" glass>
      <GlassTable
        :columns="columns"
        :rows="items"
        :loading="isLoading"
        row-key="id"
        empty-text="No content has comments yet."
        @row-click="(row: any) => router.push(`/cms/comments/${row.id}`)"
      >
        <template #col-name="{ row }">
          <div class="name-text">
            <div class="item-name">{{ row.name }}</div>
            <div class="mono item-id">{{ row.id }}</div>
          </div>
        </template>
        <template #col-type="{ row }">{{ contentLabel(row as Row) }}</template>
        <template #col-last="{ row }">{{ relativeTime((row as Row).lastCommentAt) }}</template>
        <template #col-moderation="{ row }">
          <Badge v-if="(row as Row).hasUnmoderatedComments" color="#f97316">Needs review</Badge>
          <span v-else class="muted-dash">—</span>
        </template>
      </GlassTable>
    </SectionCard>

    <div v-if="offset > 0 || hasNext" class="pager">
      <Button
        size="sm"
        icon="arrow-left"
        :disabled="offset === 0"
        @click="prevPage">Prev</Button>
      <span class="pager-info mono">page {{ currentPage }}</span>
      <Button size="sm" :disabled="!hasNext" @click="nextPage">Next</Button>
    </div>
  </PageShell>
</template>

<style scoped>
.name-text { min-width: 0; }
.item-name { font-weight: 500; color: var(--fg-0); }
.item-id { font-size: 11px; color: var(--fg-4); margin-top: 1px; }
.muted-dash { color: var(--fg-4); }
.pager { display: flex; align-items: center; gap: 12px; justify-content: center; margin-top: 14px; }
.pager-info { font-size: 11px; color: var(--fg-3); }
</style>
