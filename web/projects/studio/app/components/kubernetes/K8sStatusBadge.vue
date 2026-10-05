<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  status: string
  pulse?: boolean
  /** Render only the colored dot — the status text moves to title/aria-label. */
  hideLabel?: boolean
}>(), { pulse: false, hideLabel: false })

type Tone = 'ok' | 'warn' | 'err' | 'muted'
const tone = computed<Tone>(() => {
  const s = (props.status || '').toLowerCase()
  if ([
    'running', 'ready', 'active', 'complete', 'completed', 'ok', 'healthy', 'succeeded',
    'deployed', 'accepted', 'programmed', 'bound', 'scaling',
  ].includes(s)) return 'ok'
  if ([
    'pending', 'containercreating', 'podinitializing', 'progressing', 'warn', 'degraded',
    'resizing', 'renewing', 'limited', 'blocked',
  ].includes(s)) return 'warn'
  if ([
    'crashloop', 'crashloopbackoff', 'imagepullbackoff', 'error', 'errimagepull',
    'failed', 'failing', 'err', 'diskpressure', 'memorypressure', 'notready', 'released',
  ].includes(s)) return 'err'
  return 'muted'
})

const TONE_COLOR: Record<Tone, string> = {
  ok: 'var(--ok, #34d99a)',
  warn: 'var(--warn, #ffb547)',
  err: 'var(--err, #ff5d6c)',
  muted: 'var(--fg-3, #9b9ba5)',
}
</script>

<template>
  <span
    class="k8s-badge"
    :class="[tone, { pulse, 'dot-only': hideLabel }]"
    :style="{ '--bdg': TONE_COLOR[tone] }"
    :role="hideLabel ? 'img' : undefined"
    :title="hideLabel ? status : undefined"
    :aria-label="hideLabel ? status : undefined"
  >
    <span class="dot" />
    <span v-if="!hideLabel" class="label">{{ status }}</span>
  </span>
</template>

<style scoped>
.k8s-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px 2px 7px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 530;
  line-height: 1.45;
  color: var(--bdg);
  background: color-mix(in oklab, var(--bdg) 12%, transparent);
  border: 1px solid color-mix(in oklab, var(--bdg) 28%, transparent);
}
.k8s-badge.dot-only {
  padding: 0;
  border: none;
  background: transparent;
}
.k8s-badge.dot-only .dot {
  width: 8px;
  height: 8px;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--bdg);
  flex-shrink: 0;
}
.pulse .dot {
  box-shadow: 0 0 0 0 color-mix(in oklab, var(--bdg) 70%, transparent);
  animation: k8s-pulse 1.4s infinite ease-out;
}
@keyframes k8s-pulse {
  0% { box-shadow: 0 0 0 0 color-mix(in oklab, var(--bdg) 70%, transparent); }
  70% { box-shadow: 0 0 0 6px transparent; }
  100% { box-shadow: 0 0 0 0 transparent; }
}
</style>
