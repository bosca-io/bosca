<script setup lang="ts">
import { ref } from 'vue'
import type { K8sApplyResult } from '~/composables/useK8sMutations'

/**
 * Paste-a-YAML modal for `kubectl apply` style operations.
 *
 * Wraps the `applyManifest` GraphQL mutation. The flow:
 *  1. User pastes YAML into the CodeEditor.
 *  2. "Dry run" submits with `dryRun=true` — the controller runs
 *     server-side dry run, returns a per-resource report, no cluster
 *     state changes.
 *  3. "Apply" submits with `dryRun=false`. Same result shape.
 *  4. Outcome panel lists applied resources and failed resources with
 *     their per-resource error messages.
 *
 * Errors at the backend level (cluster unreachable, auth denied)
 * surface as a top-of-modal banner. Per-resource failures live in the
 * result panel.
 */

const props = defineProps<{ accent?: string }>()
const emit = defineEmits<{ close: []; applied: [result: K8sApplyResult] }>()

const { current } = useK8sCluster()
const clusterIdRef = computed(() => current.value?.id)
const mutations = useK8sMutations({ cluster: clusterIdRef })
const toast = useToast()

const manifest = ref('')
const submitting = ref(false)
const banner = ref<string | null>(null)
const result = ref<K8sApplyResult | null>(null)

async function run(dryRun: boolean) {
  if (!manifest.value.trim()) {
    banner.value = 'Paste a YAML manifest before submitting.'
    return
  }
  if (!clusterIdRef.value) {
    banner.value = 'Select a cluster before applying.'
    return
  }
  banner.value = null
  result.value = null
  submitting.value = true
  try {
    const r = await mutations.applyManifest({ manifest: manifest.value, dryRun })
    result.value = r
    if (r.succeeded) {
      toast.success(dryRun
        ? `Dry-run OK — ${r.applied.length} resource${r.applied.length === 1 ? '' : 's'} would apply`
        : `Applied ${r.applied.length} resource${r.applied.length === 1 ? '' : 's'}`,
      )
      if (!dryRun) emit('applied', r)
    } else {
      toast.error(`${r.failed.length} resource${r.failed.length === 1 ? '' : 's'} failed`)
    }
  } catch (err: unknown) {
    banner.value = err instanceof Error ? err.message : 'Apply failed'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Modal
    title="Apply manifest"
    subtitle="Paste a YAML manifest. Multi-document (---) inputs are accepted."
    icon="arrowUpRight"
    :accent="props.accent ?? '#326ce5'"
    width="720px"
    @close="emit('close')"
  >
    <div v-if="banner" class="banner">{{ banner }}</div>

    <CodeEditor
      v-model="manifest"
      language="text"
      :rows="18"
      placeholder="apiVersion: v1
kind: ConfigMap
metadata:
  name: example
  namespace: default
data:
  key: value"
    />

    <div v-if="result" class="result">
      <div class="result-head">
        <span class="result-status" :class="result.succeeded ? 'ok' : 'err'">
          {{ result.succeeded ? 'Succeeded' : 'Partial failure' }}
        </span>
        <span class="muted">{{ result.applied.length }} applied · {{ result.failed.length }} failed</span>
      </div>
      <div v-if="result.applied.length" class="result-section">
        <div class="result-label">Applied</div>
        <ul class="result-list">
          <li v-for="r in result.applied" :key="r" class="mono ok">{{ r }}</li>
        </ul>
      </div>
      <div v-if="result.failed.length" class="result-section">
        <div class="result-label">Failed</div>
        <ul class="result-list">
          <li v-for="f in result.failed" :key="f.resource" class="failed">
            <span class="mono">{{ f.resource }}</span>
            <span class="err-text">{{ f.error }}</span>
          </li>
        </ul>
      </div>
      <pre v-if="result.dryRun" class="dryrun mono">{{ result.dryRun }}</pre>
    </div>

    <template #footer>
      <Button size="sm" :disabled="submitting" @click="emit('close')">Close</Button>
      <Button size="sm" :disabled="submitting" @click="run(true)">
        {{ submitting ? 'Working…' : 'Dry run' }}
      </Button>
      <Button
        size="sm"
        variant="primary"
        :disabled="submitting"
        @click="run(false)">
        {{ submitting ? 'Applying…' : 'Apply' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.banner {
  background: color-mix(in oklch, var(--err) 12%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  color: var(--fg-1);
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
  font-size: 13px;
}

.result { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 14px; }
.result-head { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; }
.result-status {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 3px 8px;
  border-radius: 4px;
}
.result-status.ok {
  background: color-mix(in oklch, var(--ok) 18%, var(--bg-2));
  color: var(--ok);
}
.result-status.err {
  background: color-mix(in oklch, var(--err) 18%, var(--bg-2));
  color: var(--err);
}
.muted { color: var(--fg-3); font-size: 12px; }

.result-section { margin-bottom: 10px; }
.result-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
  margin-bottom: 4px;
}
.result-list { margin: 0; padding: 0; list-style: none; }
.result-list li { padding: 3px 0; font-size: 12.5px; }
.result-list .ok { color: var(--fg-1); }
.result-list .failed { display: grid; grid-template-columns: 1fr 2fr; gap: 12px; }
.err-text { color: var(--err); font-size: 12px; }

.dryrun {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 10px 12px;
  margin-top: 12px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
