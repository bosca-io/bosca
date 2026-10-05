<script setup lang="ts">
const queries = [
  { q: 'pricing tier compare',    n: 412, t: 'up'   },
  { q: 'enterprise SSO',          n: 308, t: 'up'   },
  { q: 'refund policy 2025',      n: 271, t: 'flat' },
  { q: 'webinar replay october',  n: 184, t: 'down' },
  { q: 'api v3 migration',        n: 142, t: 'up'   },
  { q: 'press kit',               n: 96,  t: 'flat' },
]
</script>

<template>
  <div class="search-misses">
    <div
      v-for="(m, i) in queries"
      :key="i"
      class="search-miss-row"
      :style="{ borderBottom: i === queries.length - 1 ? 'none' : '1px dashed var(--line)' }"
    >
      <!-- Rank -->
      <span class="search-miss-rank">{{ i + 1 }}</span>

      <!-- Query text -->
      <span class="search-miss-query">{{ m.q }}</span>

      <!-- Trend arrow -->
      <span class="search-miss-trend" :style="{ color: m.t === 'up' ? 'var(--err)' : m.t === 'down' ? 'var(--ok)' : 'var(--fg-3)' }">
        {{ m.t === 'up' ? '▲' : m.t === 'down' ? '▼' : '—' }}
      </span>

      <!-- Count -->
      <span class="search-miss-count tabular">{{ m.n }}</span>
    </div>
  </div>
</template>

<style scoped>
.search-misses {
  display: flex;
  flex-direction: column;
}

.search-miss-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
}

.search-miss-rank {
  font-size: 11px;
  color: var(--fg-4);
  width: 18px;
  text-align: right;
}

.search-miss-query {
  font-size: 13px;
  color: var(--fg-0);
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.search-miss-trend {
  font-size: 11px;
}

.search-miss-count {
  font-size: 12px;
  color: var(--fg-1);
  font-weight: 600;
  width: 40px;
  text-align: right;
}
</style>
