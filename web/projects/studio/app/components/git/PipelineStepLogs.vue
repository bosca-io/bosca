<script setup lang="ts">
import gql from 'graphql-tag'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { usePipelineLogs, type PipelineLogLine } from '~/composables/usePipelineLogs'

const props = defineProps<{
  repositoryId: string
  stepId: string
  /**
   * True while the step is QUEUED or RUNNING. When false we still
   * show whatever historical lines exist, but no live subscription
   * runs — saves a WebSocket per terminal step in long pipelines.
   */
  active: boolean
}>()

const { query } = useGraphQL()

const initialLoading = ref(true)

// Drive the live subscription refs from `active`. When the step
// finishes mid-view, these go null and the composable's internal
// watch tears the WebSocket down.
const repoIdRef = computed(() => (props.active ? props.repositoryId : null))
const stepIdRef = computed(() => (props.active ? props.stepId : null))

const {
  lines,
  paused,
  status,
  togglePause,
  seed,
} = usePipelineLogs({
  repositoryId: repoIdRef,
  stepId: stepIdRef,
  // Above the subscription default so backwards paging via
  // loadEarlier() isn't immediately trimmed back off the head.
  maxLines: 20000,
})

const PAGE_SIZE = 1000

const LOGS_QUERY = gql`
  query StepLogs($stepId: UUID!, $limit: Int, $tail: Boolean, $beforeLine: Int) {
    git {
      pipelineLogs(stepId: $stepId, limit: $limit, tail: $tail, beforeLine: $beforeLine) {
        lineNumber
        content
        stream
        timestamp
      }
    }
  }
`

// Fetch the END of the log, not the start: failure output (a gradle
// error, a stack trace) is on the last lines. Earlier lines stay
// reachable through loadEarlier().
async function fetchHistorical() {
  try {
    const result = await query<{ git: { pipelineLogs: PipelineLogLine[] } }>(
      LOGS_QUERY,
      { stepId: props.stepId, limit: PAGE_SIZE, tail: true },
    )
    seed(result.git?.pipelineLogs ?? [])
  } catch (err) {
    console.warn('Failed to load pipeline step logs', err)
  } finally {
    initialLoading.value = false
  }
}

const firstLineNumber = computed(() => lines.value[0]?.lineNumber ?? null)
// Stored line numbers can have gaps (the agent drops a batch after
// upload-retry exhaustion), so `firstLineNumber - 1` is only an
// estimate of what remains. When the server confirms nothing earlier
// exists (empty page), the button disappears for good.
const noEarlierLines = ref(false)
const earlierCount = computed(() => {
  if (noEarlierLines.value) return 0
  return firstLineNumber.value != null ? Math.max(0, firstLineNumber.value - 1) : 0
})
const loadingEarlier = ref(false)

async function loadEarlier() {
  const first = firstLineNumber.value
  if (!first || first <= 1 || loadingEarlier.value) return
  loadingEarlier.value = true
  try {
    // Page backwards by line number, not positional offset: every
    // returned line has lineNumber < first, so each successful page
    // strictly lowers firstLineNumber even across gaps.
    const result = await query<{ git: { pipelineLogs: PipelineLogLine[] } }>(
      LOGS_QUERY,
      { stepId: props.stepId, limit: PAGE_SIZE, beforeLine: first },
    )
    const batch = result.git?.pipelineLogs ?? []
    if (!batch.length) {
      noEarlierLines.value = true
      return
    }
    // Prepending grows the content above the viewport; restore the
    // scroll anchor so the lines the user was reading don't jump.
    const el = viewerEl.value
    const prevHeight = el?.scrollHeight ?? 0
    const prevTop = el?.scrollTop ?? 0
    seed(batch)
    await nextTick()
    if (el) el.scrollTop = prevTop + (el.scrollHeight - prevHeight)
  } catch (err) {
    console.warn('Failed to load earlier pipeline step logs', err)
  } finally {
    loadingEarlier.value = false
  }
}

onMounted(fetchHistorical)

const viewerEl = ref<HTMLElement | null>(null)

// Follow the newest output — the failure cause lives on the last
// lines — unless the user scrolled up to read something, in which
// case stay put until they return to (near) the bottom.
const stickToBottom = ref(true)

function onViewerScroll() {
  const el = viewerEl.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 24
}

watch(
  () => lines.value.length,
  async () => {
    if (!stickToBottom.value || loadingEarlier.value) return
    await nextTick()
    const el = viewerEl.value
    if (el) el.scrollTop = el.scrollHeight
  },
)

// When the step transitions active → inactive, run one more query
// to backfill any lines the agent flushed after we received the
// last pub/sub message but before the subscription completed.
watch(
  () => props.active,
  (isActive, wasActive) => {
    if (wasActive && !isActive) fetchHistorical()
  },
)

const statusLabel = computed(() => {
  if (!props.active) return null
  if (status.value === 'streaming') return 'live'
  if (status.value === 'connecting') return 'connecting…'
  if (status.value === 'reconnecting') return 'reconnecting…'
  if (status.value === 'error') return 'error'
  return null
})

const hasLines = computed(() => lines.value.length > 0)
</script>

<template>
  <div class="step-logs">
    <div v-if="initialLoading && !hasLines" class="log-loading">Loading logs…</div>
    <template v-else>
      <div v-if="active" class="log-controls">
        <span
          v-if="statusLabel"
          class="log-status"
          :class="`log-status--${status}`"
        >{{ statusLabel }}</span>
        <button class="log-control-btn" @click="togglePause">
          {{ paused ? 'Resume' : 'Pause' }}
        </button>
      </div>
      <div v-if="!hasLines" class="log-empty">No log output.</div>
      <div
        v-else
        ref="viewerEl"
        class="log-viewer"
        @scroll.passive="onViewerScroll"
      >
        <button
          v-if="earlierCount > 0"
          class="log-load-earlier"
          :disabled="loadingEarlier"
          @click="loadEarlier"
        >
          {{ loadingEarlier ? 'Loading…' : `Load earlier lines (${earlierCount.toLocaleString()} more)` }}
        </button>
        <div
          v-for="line in lines"
          :key="line.lineNumber"
          class="log-line"
          :class="{ stderr: line.stream?.toLowerCase() === 'stderr' }"
        >
          <span class="line-number">{{ line.lineNumber }}</span>
          <span class="line-content">{{ line.content }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.step-logs { background: var(--bg-0); border-top: 1px solid var(--line); }

.log-loading, .log-empty {
  text-align: center; padding: 24px; color: var(--fg-3); font-size: 12.5px;
}

.log-controls {
  display: flex; align-items: center; gap: 10px;
  padding: 6px 12px; border-bottom: 1px solid var(--line);
  font-size: 11px; color: var(--fg-3);
}

.log-status {
  display: inline-flex; align-items: center;
  padding: 2px 8px; border-radius: 6px;
  background: color-mix(in oklch, var(--fg-3) 10%, transparent);
  letter-spacing: 0.03em;
}
.log-status--streaming {
  color: var(--ok);
  background: color-mix(in oklch, var(--ok) 14%, transparent);
}
.log-status--reconnecting,
.log-status--connecting {
  color: var(--warn);
  background: color-mix(in oklch, var(--warn) 14%, transparent);
}
.log-status--error {
  color: var(--err);
  background: color-mix(in oklch, var(--err) 14%, transparent);
}

.log-control-btn {
  background: var(--bg-2); border: 1px solid var(--line); border-radius: 6px;
  color: var(--fg-1); font: inherit; font-size: 11px;
  padding: 2px 10px; cursor: pointer;
}
.log-control-btn:hover { color: var(--fg-0); background: var(--bg-3); }

.log-viewer {
  max-height: 420px; overflow: auto;
  font-family: var(--font-mono); font-size: 11.5px;
  padding: 8px 0;
}
.log-load-earlier {
  display: block; width: calc(100% - 24px); margin: 0 12px 6px;
  background: var(--bg-2); border: 1px dashed var(--line); border-radius: 6px;
  color: var(--fg-2); font: inherit; font-size: 11px;
  padding: 4px 10px; cursor: pointer;
}
.log-load-earlier:hover:not(:disabled) { color: var(--fg-0); background: var(--bg-3); }
.log-load-earlier:disabled { cursor: default; opacity: 0.6; }
.log-line { display: flex; gap: 12px; padding: 1px 12px; line-height: 1.6; }
.log-line.stderr { color: var(--err); }
.line-number {
  color: var(--fg-4); width: 40px; text-align: right;
  user-select: none; flex-shrink: 0;
}
.line-content { white-space: pre-wrap; word-break: break-all; }
</style>
