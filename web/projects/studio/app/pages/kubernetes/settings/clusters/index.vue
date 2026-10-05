<script setup lang="ts">
import { ref } from 'vue'
import type { GlassTableColumn, OverflowMenuItem } from '@bosca/ui'
import type { K8sClusterEnvironment } from '~/composables/useK8sMutations'

const { accent } = useCurrentSubsystem()
const { clusters, current, setCluster, refresh } = useK8sCluster()
const { registerCluster, updateCluster, rotateKubeconfig, removeCluster } = useK8sClusterMutations()
const toast = useToast()

type EnvSlug = 'production' | 'staging' | 'dev'

const ENV_TO_BACKEND: Record<EnvSlug, K8sClusterEnvironment> = {
  production: 'PRODUCTION',
  staging: 'STAGING',
  dev: 'DEVELOPMENT',
}

const showRegister = ref(false)
const showRemove = ref<string | null>(null)
const showEdit = ref<K8sCluster | null>(null)
const showRotate = ref<K8sCluster | null>(null)

const registerForm = ref({
  name: '',
  provider: '',
  region: '',
  env: 'production' as EnvSlug,
  kubeconfig: '',
})
const editForm = ref({ name: '', env: 'production' as EnvSlug })
const rotateForm = ref({ kubeconfig: '' })
const saving = ref(false)

const envOptions = [
  { label: 'Production', value: 'production' },
  { label: 'Staging', value: 'staging' },
  { label: 'Development', value: 'dev' },
]

const columns: GlassTableColumn[] = [
  { key: 'name', label: 'Cluster', width: 'minmax(220px, 1.5fr)' },
  { key: 'provider', label: 'Provider', width: '110px' },
  { key: 'region', label: 'Region', width: '160px', muted: true },
  { key: 'env', label: 'Environment', width: '130px' },
  { key: 'version', label: 'Version', width: '100px', muted: true },
  { key: 'health', label: 'Health', width: '110px' },
  { key: 'nodes', label: 'Nodes', width: '80px', align: 'right' },
  { key: 'pods', label: 'Pods', width: '80px', align: 'right' },
]

function rowActions(): OverflowMenuItem[] {
  return [
    { id: 'select', label: 'Use this cluster', icon: 'check' },
    { id: 'edit', label: 'Edit', icon: 'pencil' },
    { id: 'rotate', label: 'Rotate kubeconfig', icon: 'refresh' },
    { id: 'separator', label: '', separator: true },
    { id: 'remove', label: 'Remove', icon: 'trash', danger: true },
  ]
}

function onAction({ action, row }: { action: string; row: K8sCluster }) {
  if (action === 'select') {
    setCluster(row.id)
    toast.success(`Switched to ${row.name}`)
  } else if (action === 'edit') {
    editForm.value = { name: row.name, env: row.env }
    showEdit.value = row
  } else if (action === 'rotate') {
    rotateForm.value = { kubeconfig: '' }
    showRotate.value = row
  } else if (action === 'remove') {
    showRemove.value = row.id
  }
}

function errorMessage(err: unknown): string {
  if (err instanceof Error && err.message) return err.message
  return 'Unknown error'
}

async function submitRegister() {
  const f = registerForm.value
  if (!f.name.trim() || !f.provider.trim() || !f.region.trim() || !f.kubeconfig.trim()) return
  saving.value = true
  try {
    const cluster = await registerCluster({
      name: f.name.trim(),
      provider: f.provider.trim(),
      region: f.region.trim(),
      environment: ENV_TO_BACKEND[f.env],
      kubeconfig: f.kubeconfig,
    })
    await refresh()
    showRegister.value = false
    registerForm.value = { name: '', provider: '', region: '', env: 'production', kubeconfig: '' }
    toast.success(`Cluster ${cluster.name} registered`)
  } catch (err) {
    toast.error(`Failed to register cluster: ${errorMessage(err)}`)
  } finally {
    saving.value = false
  }
}

async function submitEdit() {
  const target = showEdit.value
  const f = editForm.value
  if (!target || !f.name.trim()) return
  saving.value = true
  try {
    const cluster = await updateCluster(target.id, {
      name: f.name.trim(),
      environment: ENV_TO_BACKEND[f.env],
    })
    await refresh()
    showEdit.value = null
    toast.success(`Cluster ${cluster.name} updated`)
  } catch (err) {
    toast.error(`Failed to update cluster: ${errorMessage(err)}`)
  } finally {
    saving.value = false
  }
}

async function submitRotate() {
  const target = showRotate.value
  const f = rotateForm.value
  if (!target || !f.kubeconfig.trim()) return
  saving.value = true
  try {
    await rotateKubeconfig(target.id, f.kubeconfig)
    await refresh()
    showRotate.value = null
    rotateForm.value = { kubeconfig: '' }
    toast.success(`Kubeconfig rotated for ${target.name}`)
  } catch (err) {
    toast.error(`Failed to rotate kubeconfig: ${errorMessage(err)}`)
  } finally {
    saving.value = false
  }
}

async function confirmRemove() {
  const id = showRemove.value
  if (!id) return
  const target = clusters.value.find(c => c.id === id)
  saving.value = true
  try {
    await removeCluster(id)
    await refresh()
    showRemove.value = null
    toast.success(`Cluster ${target?.name ?? id} removed`)
  } catch (err) {
    toast.error(`Failed to remove cluster: ${errorMessage(err)}`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Kubernetes', 'Registered Clusters')"
        title="Registered Clusters"
        :subtitle="`${clusters.length} clusters · current: ${current?.name || '—'}`"
      >
        <template #actions>
          <Button icon="refresh" size="sm" @click="refresh()">Refresh</Button>
          <Button
            primary
            icon="plus"
            size="sm"
            :accent="accent"
            @click="showRegister = true">Register cluster</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard padded glass>
      <GlassTable
        :columns="columns"
        :rows="clusters"
        row-key="id"
        :row-actions="rowActions"
        @row-action="onAction">
        <template #col-name="{ row }">
          <div class="row-name">
            <span class="dot" :class="row.health" />
            <div class="stack">
              <span class="mono name">{{ row.name }}</span>
              <span v-if="row.id === current?.id" class="dim mono">current</span>
            </div>
          </div>
        </template>
        <template #col-provider="{ row }">
          <Badge color="#5ec5ff">{{ row.provider }}</Badge>
        </template>
        <template #col-env="{ row }">
          <Badge :color="row.env === 'production' ? '#ff5d6c' : row.env === 'staging' ? '#ffb547' : '#34d99a'">{{ row.env }}</Badge>
        </template>
        <template #col-health="{ row }">
          <K8sStatusBadge :status="row.health === 'ok' ? 'Healthy' : row.health === 'warn' ? 'Degraded' : 'Failing'" :pulse="row.health !== 'ok'" />
        </template>
        <template #col-nodes="{ row }"><span class="mono">{{ row.nodes }}</span></template>
        <template #col-pods="{ row }"><span class="mono">{{ row.pods }}</span></template>
      </GlassTable>
    </SectionCard>

    <Modal
      v-if="showRegister"
      title="Register cluster"
      icon="plus"
      :accent="accent"
      @close="showRegister = false">
      <div class="form-stack">
        <TextInput v-model="registerForm.name" label="Name" placeholder="e.g. prod-us-east-1" />
        <TextInput v-model="registerForm.provider" label="Provider" placeholder="e.g. EKS, GKE, kind" />
        <TextInput v-model="registerForm.region" label="Region" placeholder="e.g. us-east-1" />
        <Select v-model="registerForm.env" label="Environment" :options="envOptions" />
        <Textarea
          v-model="registerForm.kubeconfig"
          label="Kubeconfig"
          placeholder="Paste kubeconfig YAML…"
          :rows="10" />
        <p class="hint">Kubeconfig is stored encrypted and used only by kubernetes-controller. It is never sent back to the browser.</p>
      </div>
      <template #footer>
        <Button @click="showRegister = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="submitRegister">Register</Button>
      </template>
    </Modal>

    <Modal
      v-if="showEdit"
      title="Edit cluster"
      icon="pencil"
      :accent="accent"
      @close="showEdit = null">
      <div class="form-stack">
        <TextInput v-model="editForm.name" label="Name" />
        <Select v-model="editForm.env" label="Environment" :options="envOptions" />
        <p class="hint">To replace the cluster's kubeconfig, use Rotate kubeconfig.</p>
      </div>
      <template #footer>
        <Button @click="showEdit = null">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="submitEdit">Save</Button>
      </template>
    </Modal>

    <Modal
      v-if="showRotate"
      title="Rotate kubeconfig"
      icon="refresh"
      :accent="accent"
      @close="showRotate = null">
      <div class="form-stack">
        <p class="hint">
          Replace the stored kubeconfig for <strong>{{ showRotate.name }}</strong>. The new
          kubeconfig is stored encrypted and never sent back to the browser.
        </p>
        <Textarea
          v-model="rotateForm.kubeconfig"
          label="New kubeconfig"
          placeholder="Paste kubeconfig YAML…"
          :rows="10" />
      </div>
      <template #footer>
        <Button @click="showRotate = null">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="submitRotate">Rotate</Button>
      </template>
    </Modal>

    <ConfirmModal
      v-if="showRemove"
      title="Remove cluster?"
      confirm-label="Remove"
      :loading="saving"
      @close="showRemove = null"
      @confirm="confirmRemove"
    >
      Removing a cluster disconnects Bosca but does not affect the cluster itself.
      The stored kubeconfig will be deleted.
    </ConfirmModal>
  </PageShell>
</template>

<style scoped>
.row-name { display: flex; align-items: center; gap: 10px; }
.dot {
  width: 8px; height: 8px;
  border-radius: 999px;
  background: var(--ok);
}
.dot.warn { background: var(--warn); }
.dot.err { background: var(--err); }
.stack { display: flex; flex-direction: column; line-height: 1.25; }
.name { font-weight: 500; font-size: 12.5px; }
.mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.dim { color: var(--fg-3); font-size: 10.5px; }

.form-stack { display: flex; flex-direction: column; gap: 12px; }
.hint { font-size: 11.5px; color: var(--fg-3); margin: 0; }
.hint strong { color: var(--fg-1); font-weight: 600; }
</style>
