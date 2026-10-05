<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

/**
 * Streaming log viewer. Designed for short-to-medium log buffers (kubernetes
 * pods, job runs, build output). For massive backlogs use a virtualized list
 * instead — this renders every line.
 *
 * Each line declares its own level so the viewer can color it consistently.
 * The component manages its own scroll-to-bottom behavior when `follow` is on.
 */

export interface LogLine {
  /** Timestamp string. Format is up to the caller — only displayed, not parsed. */
  t: string
  /** Log level. Determines the tone applied to the level pill and message color. */
  lvl: 'info' | 'warn' | 'error' | 'debug' | string
  /** Single message body. Multi-line content is rendered as-is (word-break: break-all). */
  msg: string
}

const props = withDefaults(defineProps<{
  lines: LogLine[]
  /** Show the search + follow controls. Default true. */
  controls?: boolean
  /** Maximum height of the scrollable log window. Any CSS length. */
  maxHeight?: string
  /** Initial value for the follow checkbox. */
  defaultFollow?: boolean
}>(), {
  controls: true,
  maxHeight: '460px',
  defaultFollow: true,
})

const search = ref('')
const follow = ref(props.defaultFollow)
const window = ref<HTMLElement | null>(null)

const filtered = computed(() =>
  props.lines.filter(l => !search.value || l.msg.toLowerCase().includes(search.value.toLowerCase())),
)

watch(filtered, async () => {
  if (!follow.value) return
  await nextTick()
  if (window.value) window.value.scrollTop = window.value.scrollHeight
}, { deep: false, flush: 'post' })
</script>

<template>
  <div class="logs">
    <div v-if="controls" class="logs-head">
      <slot name="controls-before" />
      <input
        v-model="search"
        class="log-search"
        type="search"
        placeholder="Filter…"
        aria-label="Filter log lines"
      />
      <label class="follow">
        <input v-model="follow" type="checkbox" />
        Follow
      </label>
      <slot name="controls-after" />
    </div>
    <div ref="window" class="log-window" :style="{ maxHeight }">
      <div v-if="filtered.length === 0" class="empty">
        No log lines.
      </div>
      <div v-else v-for="(l, i) in filtered" :key="i" class="log-line" :class="`lvl-${l.lvl}`">
        <span class="t">{{ l.t }}</span>
        <span class="lvl">{{ l.lvl.toUpperCase() }}</span>
        <span class="msg">{{ l.msg }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.logs { display: flex; flex-direction: column; gap: 10px; min-height: 0; }

.logs-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.log-search {
  flex: 1;
  min-width: 200px;
  max-width: 320px;
  padding: 6px 10px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  color: var(--fg-0);
  font: inherit;
  font-size: 12.5px;
}
.log-search:focus { outline: none; border-color: var(--brand-2); }

.follow {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--fg-2);
  user-select: none;
  cursor: pointer;
}

.log-window {
  font-family: var(--font-mono);
  font-size: 11.5px;
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px;
  overflow: auto;
  min-height: 80px;
}

.empty {
  padding: 20px;
  text-align: center;
  color: var(--fg-3);
  font-family: var(--font-sans);
  font-size: 12px;
}

.log-line {
  display: grid;
  grid-template-columns: 90px 50px 1fr;
  gap: 10px;
  padding: 2px 0;
  align-items: baseline;
}
.log-line .t { color: var(--fg-3); }
.log-line .lvl { color: var(--fg-3); font-size: 10px; font-weight: 600; }
.log-line.lvl-warn .lvl { color: var(--warn); }
.log-line.lvl-error .lvl { color: var(--err); }
.log-line.lvl-info .lvl { color: var(--info); }
.log-line.lvl-debug .lvl { color: var(--fg-3); }
.log-line .msg { color: var(--fg-1); word-break: break-all; }
.log-line.lvl-error .msg { color: var(--err); }
</style>
