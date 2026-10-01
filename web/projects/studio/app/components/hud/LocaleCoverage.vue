<script setup lang="ts">
const locales = [
  { tag: 'en-US', name: 'English (US)',  pct: 100, base: true  },
  { tag: 'es-ES', name: 'Spanish',       pct: 94               },
  { tag: 'fr-FR', name: 'French',        pct: 88               },
  { tag: 'de-DE', name: 'German',        pct: 76               },
  { tag: 'pt-BR', name: 'Portuguese',    pct: 62               },
  { tag: 'ja-JP', name: 'Japanese',      pct: 41               },
  { tag: 'ar-SA', name: 'Arabic',        pct: 18               },
]

function tone(pct: number) {
  if (pct >= 90) return 'var(--ok)'
  if (pct >= 70) return 'var(--info)'
  if (pct >= 40) return 'var(--warn)'
  return 'var(--err)'
}
</script>

<template>
  <div class="locale-coverage">
    <div
      v-for="l in locales"
      :key="l.tag"
      class="locale-row"
    >
      <!-- Tag -->
      <span class="locale-tag mono">{{ l.tag }}</span>

      <!-- Progress bar -->
      <div class="locale-bar-track">
        <div
          class="locale-bar-fill"
          :style="{
            width: `${l.pct}%`,
            background: l.base ? 'var(--brand-grad)' : tone(l.pct),
          }"
        />
      </div>

      <!-- Percentage -->
      <span class="locale-pct tabular">{{ l.pct }}%</span>
    </div>
  </div>
</template>

<style scoped>
.locale-coverage {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.locale-row {
  display: grid;
  grid-template-columns: 60px 1fr 50px;
  gap: 10px;
  align-items: center;
}

.locale-tag {
  font-size: 11.5px;
  color: var(--fg-2);
}

.locale-bar-track {
  height: 8px;
  background: var(--bg-2);
  border-radius: 4px;
  overflow: hidden;
  position: relative;
}

.locale-bar-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
}

.locale-pct {
  font-size: 12px;
  color: var(--fg-0);
  text-align: right;
  font-weight: 600;
}
</style>
