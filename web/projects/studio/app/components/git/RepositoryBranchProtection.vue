<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { query, mutation } = useGraphQL()
const props = defineProps<{ repositoryId: string }>()
const selectedRepoId = computed(() => props.repositoryId)
interface BranchProtectionRule {
  id: string; pattern: string; requirePullRequest: boolean; requiredApprovals: number
  dismissStaleReviews: boolean; requireCodeOwnerReview: boolean; requireLinearHistory: boolean
  allowForcePush: boolean; allowDeletion: boolean; requireStatusChecks: string[]
  repositoryId: string
}

const rules = ref<BranchProtectionRule[]>([])
const loading = ref(false)
const showCreate = ref(false)
const showEdit = ref(false)
const editTarget = ref<BranchProtectionRule | null>(null)
const saving = ref(false)
const error = ref('')

const form = reactive({
  pattern: '',
  requirePullRequest: true,
  requiredApprovals: '1',
  dismissStaleReviews: false,
  requireCodeOwnerReview: false,
  requireLinearHistory: false,
  allowForcePush: false,
  allowDeletion: false,
  requireStatusChecks: '',
})

async function loadRules() {
  if (!selectedRepoId.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await query<{ git: { branchProtectionRules: BranchProtectionRule[] } }>(gql`
      query BranchProtection($repositoryId: UUID!) {
        git { branchProtectionRules(repositoryId: $repositoryId) {
          id pattern requirePullRequest requiredApprovals
          dismissStaleReviews requireCodeOwnerReview requireLinearHistory
          allowForcePush allowDeletion requireStatusChecks
          repositoryId
        } }
      }
    `, { repositoryId: selectedRepoId.value })
    rules.value = result.git?.branchProtectionRules ?? []
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load branch protection rules.'
  }
  finally { loading.value = false }
}

watch(selectedRepoId, () => loadRules(), { immediate: true })

function resetForm() {
  form.pattern = ''
  form.requirePullRequest = true
  form.requiredApprovals = '1'
  form.dismissStaleReviews = false
  form.requireCodeOwnerReview = false
  form.requireLinearHistory = false
  form.allowForcePush = false
  form.allowDeletion = false
  form.requireStatusChecks = ''
  error.value = ''
}

function openCreate() {
  resetForm()
  showCreate.value = true
}

function openEdit(rule: BranchProtectionRule) {
  editTarget.value = rule
  form.pattern = rule.pattern
  form.requirePullRequest = rule.requirePullRequest
  form.requiredApprovals = String(rule.requiredApprovals)
  form.dismissStaleReviews = rule.dismissStaleReviews
  form.requireCodeOwnerReview = rule.requireCodeOwnerReview
  form.requireLinearHistory = rule.requireLinearHistory
  form.allowForcePush = rule.allowForcePush
  form.allowDeletion = rule.allowDeletion
  form.requireStatusChecks = (rule.requireStatusChecks || []).join(', ')
  error.value = ''
  showEdit.value = true
}

function buildInput() {
  return {
    pattern: form.pattern,
    requirePullRequest: form.requirePullRequest,
    requiredApprovals: Number(form.requiredApprovals),
    dismissStaleReviews: form.dismissStaleReviews,
    requireCodeOwnerReview: form.requireCodeOwnerReview,
    requireLinearHistory: form.requireLinearHistory,
    allowForcePush: form.allowForcePush,
    allowDeletion: form.allowDeletion,
    requireStatusChecks: form.requireStatusChecks
      ? form.requireStatusChecks.split(',').map(s => s.trim()).filter(Boolean)
      : [],
  }
}

async function handleCreate() {
  if (!form.pattern) { error.value = 'Pattern is required.'; return }
  saving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation CreateRule($repositoryId: UUID!, $input: GitBranchProtectionRuleInput!) {
        git { createBranchProtectionRule(repositoryId: $repositoryId, input: $input) { id } }
      }
    `, { repositoryId: selectedRepoId.value, input: buildInput() })
    showCreate.value = false
    await loadRules()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create rule'
  } finally {
    saving.value = false
  }
}

async function handleUpdate() {
  if (!form.pattern || !editTarget.value) { error.value = 'Pattern is required.'; return }
  saving.value = true
  error.value = ''
  try {
    await mutation(gql`
      mutation UpdateRule($id: UUID!, $input: GitBranchProtectionRuleInput!) {
        git { updateBranchProtectionRule(id: $id, input: $input) { id } }
      }
    `, { id: editTarget.value.id, input: buildInput() })
    showEdit.value = false
    await loadRules()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to update rule'
  } finally {
    saving.value = false
  }
}

async function deleteRule(id: string) {
  try {
    await mutation(gql`mutation DeleteRule($id: UUID!) { git { deleteBranchProtectionRule(id: $id) } }`, { id })
    await loadRules()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to delete rule'
  }
}
</script>

<template>
  <div class="repository-settings-panel">
    <SectionCard title="Branch protection">
      <template #right>
        <Button
          primary
          icon="plus"
          size="sm"
          :accent="accent"
          @click="openCreate">New Rule</Button>
      </template>
      <p v-if="error && !showCreate && !showEdit" role="alert" class="form-error">{{ error }}</p>
      <div v-if="loading" class="loading-state">Loading rules…</div>
      <div v-else class="rules-list">
        <div v-for="rule in rules" :key="rule.id" class="rule-card">
          <div class="rule-header">
            <Icon name="shield" :size="15" :color="accent" />
            <span class="mono rule-pattern">{{ rule.pattern }}</span>
            <div class="rule-actions">
              <button class="edit-btn" @click="openEdit(rule)">
                <Icon name="pencil" :size="13" color="var(--fg-3)" />
              </button>
              <button class="delete-btn" @click="deleteRule(rule.id)">
                <Icon name="trash" :size="13" color="var(--fg-3)" />
              </button>
            </div>
          </div>
          <div class="rule-badges">
            <Badge v-if="rule.requirePullRequest" :color="accent">Require PR</Badge>
            <Badge v-if="rule.requiredApprovals > 0" :color="accent">{{ rule.requiredApprovals }} approvals</Badge>
            <Badge v-if="rule.dismissStaleReviews" color="#ffb547">Dismiss stale</Badge>
            <Badge v-if="rule.requireCodeOwnerReview" color="#5ec5ff">Code owner</Badge>
            <Badge v-if="rule.requireLinearHistory" color="#a78bff">Linear history</Badge>
            <Badge v-if="!rule.allowForcePush" color="#34d99a">No force push</Badge>
            <Badge v-if="!rule.allowDeletion" color="#34d99a">No deletion</Badge>
            <Badge v-if="rule.requireStatusChecks?.length" color="#ffb547">
              {{ rule.requireStatusChecks.length }} status checks
            </Badge>
          </div>
        </div>
        <div v-if="!rules.length" class="empty-msg">No branch protection rules for this repository.</div>
      </div>

    </SectionCard>

    <!-- Create / Edit Modal (shared template) -->
    <Modal
      v-if="showCreate || showEdit"
      :title="showEdit ? 'Edit Rule' : 'New Branch Protection Rule'"
      icon="shield"
      :accent="accent"
      @close="showCreate = false; showEdit = false"
    >
      <div class="form-stack">
        <TextInput
          v-model="form.pattern"
          label="Branch Pattern"
          placeholder="main, release/*, feature/**"
          mono
          :disabled="showEdit" />
        <label class="checkbox-row">
          <input v-model="form.requirePullRequest" type="checkbox" >
          <span>Require pull request before merging</span>
        </label>
        <TextInput
          v-if="form.requirePullRequest"
          v-model="form.requiredApprovals"
          label="Required Approvals"
          type="number"
          placeholder="1"
        />
        <label class="checkbox-row">
          <input v-model="form.dismissStaleReviews" type="checkbox" >
          <span>Dismiss stale reviews on new commits</span>
        </label>
        <label class="checkbox-row">
          <input v-model="form.requireCodeOwnerReview" type="checkbox" >
          <span>Require code owner review</span>
        </label>
        <label class="checkbox-row">
          <input v-model="form.requireLinearHistory" type="checkbox" >
          <span>Require linear history</span>
        </label>
        <label class="checkbox-row">
          <input v-model="form.allowForcePush" type="checkbox" >
          <span>Allow force pushes</span>
        </label>
        <label class="checkbox-row">
          <input v-model="form.allowDeletion" type="checkbox" >
          <span>Allow branch deletion</span>
        </label>
        <TextInput v-model="form.requireStatusChecks" label="Required Status Checks" placeholder="ci/build, ci/test (comma-separated)" />
        <p v-if="error" class="form-error">{{ error }}</p>
      </div>
      <template #footer>
        <Button @click="showCreate = false; showEdit = false">Cancel</Button>
        <Button
          primary
          :accent="accent"
          :disabled="saving"
          @click="showEdit ? handleUpdate() : handleCreate()">
          {{ saving ? 'Saving…' : (showEdit ? 'Update Rule' : 'Create Rule') }}
        </Button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.loading-state, .empty-msg {
  color: var(--fg-3); text-align: center; padding: 48px 0; font-size: 13.5px;
}

.rules-list {
  display: flex; flex-direction: column; gap: 10px;
}

.rule-card {
  background: var(--bg-1); border: 1px solid var(--line); border-radius: 10px;
  padding: 16px;
}

.rule-header {
  display: flex; align-items: center; gap: 10px; margin-bottom: 10px;
}

.rule-pattern { font-size: 14px; color: var(--fg-0); font-weight: 600; flex: 1; }

.rule-actions { display: flex; gap: 4px; }

.edit-btn, .delete-btn {
  background: none; border: none; padding: 6px; cursor: pointer;
  border-radius: 4px; display: flex; opacity: 0.4; transition: opacity 0.1s;
}
.edit-btn:hover { opacity: 1; background: var(--bg-3); }
.delete-btn:hover { opacity: 1; background: color-mix(in oklch, var(--err) 10%, transparent); }

.rule-badges { display: flex; flex-wrap: wrap; gap: 6px; }

.form-stack { display: flex; flex-direction: column; gap: 14px; }
.form-error { color: var(--err); font-size: 12px; margin: 0; }

.checkbox-row {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; color: var(--fg-1); cursor: pointer;
}
.checkbox-row input { accent-color: v-bind(accent); }
</style>
