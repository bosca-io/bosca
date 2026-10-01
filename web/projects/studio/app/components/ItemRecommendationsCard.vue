<script setup lang="ts">
/**
 * Recommendations panel for the metadata detail page (rendered inside the "Recommendations" main tab).
 * Both item queries use anonymous transport so identity, language defaults, and visibility match
 * a public visitor. Omitting profileId on an authenticated request would select the editor's profile.
 */
import gql from 'graphql-tag'

const props = defineProps<{ metadataId: string }>()

const { useAsyncQuery } = useGraphQL()
const { accent } = useCurrentSubsystem()

type Tab = 'recommended' | 'coEngaged'
const tab = ref<Tab>('recommended')

interface ItemRec {
  id: string
  score: number
  reason: string | null
  metadata: { id: string; name: string; attributes: unknown } | null
  collection: { id: string; name: string; attributes: unknown } | null
}

const itemGql = gql`
  query ItemRecommendationsCard($metadataId: UUID!) {
    recommendation {
      recommended(metadataId: $metadataId, limit: 12) {
        id score reason
        metadata { id name attributes(filter: { attributes: ["type"] }) }
        collection { id name attributes(filter: { attributes: ["type"] }) }
      }
      coEngaged(metadataId: $metadataId, limit: 12) {
        id score reason
        metadata { id name attributes(filter: { attributes: ["type"] }) }
        collection { id name attributes(filter: { attributes: ["type"] }) }
      }
    }
  }
`
const metadataId = computed(() => props.metadataId)
const { data, status, error, refresh } = useAsyncQuery<{ recommendation: { recommended: ItemRec[]; coEngaged: ItemRec[] } }>(
  'item-recommendations-card', itemGql, { metadataId }, { anonymous: true },
)

const recommended = computed(() => (data.value?.recommendation?.recommended ?? []).filter(r => r.metadata || r.collection))
const coEngaged = computed(() => (data.value?.recommendation?.coEngaged ?? []).filter(r => r.metadata || r.collection))
const rows = computed(() => (tab.value === 'recommended' ? recommended.value : coEngaged.value))
const loading = computed(() => status.value === 'pending')
</script>

<template>
  <div class="item-recs">
    <div class="ir-bar">
      <div class="ir-seg" role="tablist">
        <button
          type="button"
          role="tab"
          class="ir-seg-btn"
          :class="{ active: tab === 'recommended' }"
          :aria-selected="tab === 'recommended'"
          @click="tab = 'recommended'">Recommended</button>
        <button
          type="button"
          role="tab"
          class="ir-seg-btn"
          :class="{ active: tab === 'coEngaged' }"
          :aria-selected="tab === 'coEngaged'"
          @click="tab = 'coEngaged'">People also viewed</button>
      </div>
      <NuxtLink class="ir-help" to="/recommendations/help" title="How recommendations work">
        <Icon name="help" :size="13" /> How this works
      </NuxtLink>
    </div>

    <p class="ir-caption">
      Anonymous visitor preview using the active model and default context and language.
      Your Studio profile and administrator visibility do not affect these results.
    </p>

    <div v-if="error" class="ir-error">
      Couldn't load anonymous recommendations: {{ error.message }}
      <Button size="xs" @click="refresh()">Retry</Button>
    </div>
    <div v-else-if="loading && !rows.length" class="ir-empty">Loading…</div>
    <div v-else-if="!rows.length" class="ir-empty">
      No publicly visible recommendations returned for this item.
    </div>
    <div v-else class="ir-list">
      <div
        v-for="r in rows"
        :key="r.id"
        class="ir-row">
        <Icon
          name="sparkles"
          :size="14"
          :color="accent"
          class="ir-row-icon" />
        <div class="ir-row-main">
          <RecommendationItemLink :metadata="r.metadata" :collection="r.collection" />
          <span v-if="r.reason" class="ir-reason">{{ r.reason }}</span>
        </div>
        <span class="ir-score">{{ r.score.toFixed(2) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.item-recs { display: flex; flex-direction: column; }
.ir-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.ir-seg { display: inline-flex; gap: 2px; padding: 3px; background: var(--bg-3); border-radius: var(--r-sm); }
.ir-seg-btn {
  appearance: none;
  border: none;
  background: none;
  padding: 5px 12px;
  font-size: 12.5px;
  color: var(--fg-3);
  border-radius: var(--r-xs);
  cursor: pointer;
  transition: background 0.1s, color 0.1s;
}
.ir-seg-btn:hover { color: var(--fg-1); }
.ir-seg-btn.active { background: var(--bg-1); color: var(--fg-1); box-shadow: inset 0 0 0 1px color-mix(in srgb, v-bind(accent) 45%, transparent); }
.ir-help { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--fg-4); transition: color 0.1s; }
.ir-help:hover { color: var(--fg-1); }
.ir-caption { font-size: 12px; color: var(--fg-4); line-height: 1.5; margin: 10px 0 12px; max-width: 68ch; }
.ir-empty { font-size: 13px; color: var(--fg-4); line-height: 1.6; max-width: 60ch; padding: 8px 0 4px; }
.ir-error { color: var(--err); font-size: 13px; display: flex; align-items: center; gap: 12px; }
.ir-list { display: flex; flex-direction: column; gap: 2px; }
.ir-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: var(--r-sm);
  background: none;
  border: none;
  text-align: left;
  width: 100%;
  transition: background 0.1s;
}
.ir-row:hover { background: var(--bg-3); }
.ir-row-icon { flex-shrink: 0; }
.ir-row-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.ir-reason { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; color: var(--fg-4); }
.ir-score { font-size: 12px; color: var(--fg-4); font-variant-numeric: tabular-nums; flex-shrink: 0; }
</style>
