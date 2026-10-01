<script setup lang="ts">
import { computed, ref } from 'vue'
import type { K8sPodWithWorkload } from '~/composables/useK8sTypes'

const props = defineProps<{
  pod: K8sPodWithWorkload | null
}>()
const emit = defineEmits<{
  close: []
  delete: [p: K8sPodWithWorkload]
}>()

const { accent } = useCurrentSubsystem()
const { current } = useK8sCluster()

const tab = ref<'overview' | 'logs' | 'manifest'>('overview')

// Live log stream — bound to the drawer's open pod. The composable
// disconnects automatically when the bound pod ref goes null (drawer
// closes).
const follow = ref(true)
const clusterIdRef = computed(() => current.value?.id)
const namespaceRef = computed(() => props.pod?.workload.ns ?? null)
const podNameRef = computed(() => props.pod?.name ?? null)
const {
  lines: logLines,
  paused: logsPaused,
  status: logStatus,
  togglePause: toggleLogPause,
  clear: clearLogs,
} = useK8sPodLogs({
  cluster: clusterIdRef,
  namespace: namespaceRef,
  pod: podNameRef,
  follow,
})

const { data: manifestYaml, status: manifestStatus, refresh: refreshManifest } = useK8sResourceYaml({
  cluster: clusterIdRef,
  kind: () => 'Pod',
  name: () => props.pod?.name ?? null,
  namespace: () => props.pod?.workload.ns ?? null,
})
const isManifestLoading = computed(() => manifestStatus.value === 'pending')

// Live refresh of the manifest tab — gated on the drawer being open
// (cluster resolves to undefined when no pod is inspected). The pod
// row itself stays live via the parent list's own watch.
useK8sResourceWatch({
  cluster: () => (props.pod ? current.value?.id : undefined),
  namespace: () => props.pod?.workload.ns,
  kinds: ['Pod'],
  onChange: refreshManifest,
})
</script>

<template>
  <Drawer
    v-if="pod"
    :title="pod.name"
    icon="container"
    :accent="accent"
    width="680px"
    @close="emit('close')"
  >
    <template v-if="pod" #title>
      <span class="mono">{{ pod.name }}</span>
    </template>
    <template v-if="pod" #subtitle>
      <span class="sub-row">
        <K8sStatusBadge :status="pod.status" :pulse="pod.status !== 'Running'" />
        <span class="sub-text">{{ pod.workload.ns }} · {{ pod.workload.kind }}/{{ pod.workload.name }}</span>
      </span>
    </template>

    <template v-if="pod" #actions>
      <NuxtLink :to="`/kubernetes/pods/${encodeURIComponent(pod.name)}`">
        <Button size="sm" icon="externalLink">Full page</Button>
      </NuxtLink>
    </template>

    <template v-if="pod">
      <div class="tabs">
        <button :class="{ on: tab === 'overview' }" @click="tab = 'overview'">Overview</button>
        <button :class="{ on: tab === 'logs' }" @click="tab = 'logs'">Logs</button>
        <button :class="{ on: tab === 'manifest' }" @click="tab = 'manifest'">Manifest</button>
      </div>

      <section v-if="tab === 'overview'" class="kv">
        <div class="k">Namespace</div><div class="v mono">{{ pod.workload.ns }}</div>
        <div class="k">Workload</div><div class="v mono">{{ pod.workload.kind }}/{{ pod.workload.name }}</div>
        <div class="k">Node</div><div class="v mono break">{{ pod.node }}</div>
        <div class="k">Phase</div><div class="v">{{ pod.status }}</div>
        <div class="k">Ready</div><div class="v mono">{{ pod.ready }}</div>
        <div class="k">Restarts</div><div class="v mono">{{ pod.restarts }}</div>
        <div class="k">CPU</div><div class="v mono">{{ pod.cpu }}m</div>
        <div class="k">Memory</div><div class="v mono">{{ pod.mem }}Mi</div>
        <div class="k">Age</div><div class="v">{{ pod.age }}</div>
        <div class="k">Image</div><div class="v mono break">{{ pod.workload.image }}</div>
      </section>

      <section v-else-if="tab === 'logs'">
        <div class="log-bar">
          <span class="log-status" :class="`log-status--${logStatus}`">
            {{ logStatus === 'streaming' ? 'streaming'
              : logStatus === 'reconnecting' ? 'reconnecting…'
                : logStatus === 'connecting' ? 'connecting…'
                  : logStatus === 'error' ? 'error'
                    : logStatus === 'closed' ? 'closed'
                      : 'idle' }}
          </span>
          <span class="spacer" />
          <Button size="sm" :icon="logsPaused ? 'play' : 'pause'" @click="toggleLogPause()">
            {{ logsPaused ? 'Resume' : 'Pause' }}
          </Button>
          <Button size="sm" icon="x" @click="clearLogs()">Clear</Button>
        </div>
        <LogViewer :lines="logLines" max-height="460px" :default-follow="follow" />
      </section>

      <section v-else>
        <div v-if="isManifestLoading" class="manifest-loading">Loading manifest…</div>
        <CodeEditor
          v-else
          :model-value="manifestYaml"
          language="text"
          :rows="20"
          readonly />
      </section>
    </template>

    <template v-if="pod" #footer>
      <Button @click="emit('close')">Close</Button>
      <Button icon="trash" @click="emit('delete', pod)">Delete</Button>
    </template>
  </Drawer>
</template>

<style scoped>
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.sub-row { display: inline-flex; align-items: center; gap: 10px; min-width: 0; }
.sub-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--line);
  margin: -4px -2px 16px;
}
.tabs button {
  padding: 8px 12px;
  font-size: 12.5px;
  color: var(--fg-3);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  margin-bottom: -1px;
}
.tabs button.on {
  color: var(--fg-0);
  border-bottom-color: var(--brand-2);
  font-weight: 500;
}

.kv {
  display: grid;
  grid-template-columns: 110px 1fr;
  row-gap: 8px;
  column-gap: 14px;
  font-size: 12.5px;
}
.k { color: var(--fg-3); font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; padding-top: 2px; }
.v { color: var(--fg-1); }
.break { word-break: break-all; }

.log-bar { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; }
.spacer { flex: 1; }
.log-status {
  font-size: 11px;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-variant-numeric: tabular-nums;
}
.log-status--streaming { color: var(--ok, #16a34a); }
.log-status--reconnecting { color: var(--warn, #d97706); }
.log-status--error { color: var(--err, #dc2626); }
.manifest-loading { padding: 18px; text-align: center; color: var(--fg-3); font-size: 12.5px; }
</style>
