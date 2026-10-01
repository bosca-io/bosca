<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { K8sCluster } from '~/composables/useK8sTypes'

const props = defineProps<{
  cluster: K8sCluster | null
  clusters: K8sCluster[]
}>()
const emit = defineEmits<{ pick: [id: string] }>()

const open = ref(false)
const root = ref<HTMLElement | null>(null)

function onDocumentClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) {
    open.value = false
  }
}
onMounted(() => document.addEventListener('click', onDocumentClick, true))
onUnmounted(() => document.removeEventListener('click', onDocumentClick, true))

const production = computed(() => props.clusters.filter(c => c.env === 'production'))
const nonProduction = computed(() => props.clusters.filter(c => c.env !== 'production'))

function pulseClass(c: K8sCluster | null | undefined): string {
  if (!c) return ''
  if (c.health === 'ok') return 'ok'
  if (c.health === 'warn') return 'amber'
  return 'red'
}
</script>

<template>
  <div ref="root" class="cluster-switch-wrap">
    <button class="cluster-switch" :class="{ open }" @click="open = !open">
      <span class="pulse" :class="pulseClass(cluster)"><span /></span>
      <span class="name">{{ cluster?.name || 'Select cluster' }}</span>
      <span class="chev"><Icon name="chevronDown" :size="14" /></span>
    </button>

    <div v-if="open" class="popover" role="listbox">
      <div v-if="production.length" class="pop-section">Production</div>
      <button
        v-for="c in production"
        :key="c.id"
        class="pop-item"
        :class="{ on: c.id === cluster?.id }"
        role="option"
        :aria-selected="c.id === cluster?.id"
        @click="emit('pick', c.id); open = false"
      >
        <span class="pulse" :class="pulseClass(c)"><span /></span>
        <span class="pop-meta">
          <span class="name">{{ c.name }}</span>
          <span class="region">{{ c.provider }} · {{ c.region }} · {{ c.nodes }} nodes · {{ c.pods }} pods</span>
        </span>
        <span v-if="c.id === cluster?.id" class="checkmark"><Icon name="check" :size="13" /></span>
      </button>

      <div v-if="nonProduction.length" class="pop-section">Non-production</div>
      <button
        v-for="c in nonProduction"
        :key="c.id"
        class="pop-item"
        :class="{ on: c.id === cluster?.id }"
        role="option"
        :aria-selected="c.id === cluster?.id"
        @click="emit('pick', c.id); open = false"
      >
        <span class="pulse" :class="pulseClass(c)"><span /></span>
        <span class="pop-meta">
          <span class="name">{{ c.name }}</span>
          <span class="region">{{ c.provider }} · {{ c.region }} · {{ c.nodes }} nodes · {{ c.pods }} pods</span>
        </span>
        <span v-if="c.id === cluster?.id" class="checkmark"><Icon name="check" :size="13" /></span>
      </button>

      <div class="pop-divider" />
      <NuxtLink class="pop-item add" to="/kubernetes/settings/clusters" @click="open = false">
        <Icon name="plus" :size="14" />
        <span>Add cluster…</span>
      </NuxtLink>
    </div>
  </div>
</template>

<style scoped>
.cluster-switch-wrap { position: relative; min-width: 180px; }

.cluster-switch {
  width: 100%;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--bg-2, #14141b);
  border: 1px solid var(--line-2);
  border-radius: var(--r-sm, 8px);
  color: var(--fg-1);
  cursor: pointer;
  font: inherit;
  font-size: 12.5px;
  line-height: 1;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
}
.cluster-switch:hover { border-color: var(--line-2); background: var(--bg-3, #181820); }
.cluster-switch.open { border-color: var(--line-2); }

.pulse { width: 14px; height: 14px; display: grid; place-items: center; flex-shrink: 0; }
.pulse > span {
  width: 8px; height: 8px;
  border-radius: 999px;
  background: var(--ok, oklch(0.74 0.16 155));
  box-shadow: 0 0 0 0 color-mix(in oklab, var(--ok, oklch(0.74 0.16 155)) 70%, transparent);
  animation: cs-pulse 2.4s infinite ease-out;
}
.pulse.amber > span { background: var(--warn, oklch(0.78 0.15 80)); box-shadow: 0 0 0 0 color-mix(in oklab, var(--warn, oklch(0.78 0.15 80)) 70%, transparent); }
.pulse.red > span { background: var(--err, oklch(0.68 0.21 25)); box-shadow: 0 0 0 0 color-mix(in oklab, var(--err, oklch(0.68 0.21 25)) 70%, transparent); animation-duration: 1.2s; }
@keyframes cs-pulse {
  0% { box-shadow: 0 0 0 0 color-mix(in oklab, currentColor 70%, transparent); }
  70% { box-shadow: 0 0 0 8px transparent; }
  100% { box-shadow: 0 0 0 0 transparent; }
}

.pop-meta { display: flex; flex-direction: column; flex: 1; min-width: 0; }
.name { flex: 1; min-width: 0; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pop-meta .name { font-size: 13px; }
.pop-meta .region { font-size: 11px; color: var(--fg-3, #9b9ba5); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chev { color: var(--fg-3, #9b9ba5); display: grid; place-items: center; flex-shrink: 0; }

.popover {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  min-width: 320px;
  max-width: min(420px, calc(100vw - 24px));
  z-index: 30;
  background: var(--bg-1, #101015);
  border: 1px solid var(--line-2);
  border-radius: 10px;
  padding: 6px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45), 0 20px 60px rgba(0, 0, 0, 0.5);
}
.pop-section {
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3, #9b9ba5);
  padding: 8px 8px 4px;
}
.pop-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  border-radius: 6px;
}
.pop-item:hover { background: var(--bg-2, #14141b); }
.pop-item.on { background: color-mix(in oklab, var(--brand-2, #326ce5) 14%, transparent); }
.pop-item.add { color: var(--fg-2, #c2c2c8); }
.checkmark { color: var(--brand-2, #326ce5); display: grid; place-items: center; }
.pop-divider { height: 1px; background: var(--line); margin: 6px 0; }
</style>
