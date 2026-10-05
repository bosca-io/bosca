<script setup lang="ts">
import gql from 'graphql-tag'

const { accent } = useCurrentSubsystem()
const { mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const clearCacheGql = gql`
  mutation ClearCache { clearCache }
`

const clearCdnCacheGql = gql`
  mutation ClearCdnCache { clearCdnCache }
`

const expireAllJobsGql = gql`
  mutation ExpireAllJobs { expireAllJobs }
`

const clearJobLocksGql = gql`
  mutation ClearJobLocks { clearJobLocks }
`

interface UtilityAction {
  id: string
  title: string
  description: string
  icon: string
  mutation: ReturnType<typeof gql>
  dangerous: boolean
}

const actions: UtilityAction[] = [
  {
    id: 'clear-cache',
    title: 'Clear Cache',
    description: 'Clear all server-side caches (local Caffeine cache, Redis/NATS cache). Use when stale data is being served.',
    icon: 'trash',
    mutation: clearCacheGql,
    dangerous: false,
  },
  {
    id: 'clear-cdn-cache',
    title: 'Clear CDN Cache',
    description: 'Invalidate the CDN edge cache, forcing content to be re-fetched from the origin. Use when updated content is not propagating.',
    icon: 'globe',
    mutation: clearCdnCacheGql,
    dangerous: false,
  },
  {
    id: 'expire-all-jobs',
    title: 'Expire All Jobs',
    description: 'Expire all pending jobs in the queue, marking them for re-processing. This may cause significant system load as all jobs re-execute.',
    icon: 'clock',
    mutation: expireAllJobsGql,
    dangerous: true,
  },
  {
    id: 'clear-job-locks',
    title: 'Clear Job Locks',
    description: 'Force-release all distributed locks held by jobs across all queues. Only use if jobs are stuck and cannot self-recover.',
    icon: 'lock',
    mutation: clearJobLocksGql,
    dangerous: true,
  },
]

const loadingActions = reactive<Record<string, boolean>>({})

async function executeAction(action: UtilityAction) {
  loadingActions[action.id] = true
  try {
    await gqlMutation(action.mutation)
    toast.success(`${action.title} completed successfully`)
  } catch {
    toast.error(`${action.title} failed`)
  } finally {
    loadingActions[action.id] = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('System', 'Utilities')"
        title="Utilities"
        subtitle="System utility operations"
      />
    </template>

    <div class="actions-list">
      <SectionCard v-for="action in actions" :key="action.id" :title="action.title">
        <div class="action-row">
          <div class="action-content">
            <div class="action-icon-wrap" :class="{ dangerous: action.dangerous }">
              <Icon :name="action.icon" :size="18" :color="action.dangerous ? 'var(--err)' : 'var(--fg-2)'" />
            </div>
            <div class="action-text">
              <p class="action-desc">{{ action.description }}</p>
            </div>
          </div>
          <Button
            size="sm"
            primary
            :accent="action.dangerous ? 'var(--err)' : accent"
            :disabled="loadingActions[action.id]"
            @click="executeAction(action)"
          >
            {{ loadingActions[action.id] ? 'Running…' : 'Execute' }}
          </Button>
        </div>
      </SectionCard>
    </div>
  </PageShell>
</template>

<style scoped>
.actions-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.action-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
}

.action-content {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  flex: 1;
  min-width: 0;
}

.action-icon-wrap {
  width: 36px;
  height: 36px;
  border-radius: var(--r-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-2);
  flex-shrink: 0;
}

.action-icon-wrap.dangerous {
  background: color-mix(in oklch, var(--err) 12%, transparent);
}

.action-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.action-desc {
  font-size: 12.5px;
  color: var(--fg-2);
  margin: 0;
  line-height: 1.5;
}

.action-warn {
  font-size: 11px;
  color: var(--err);
  font-weight: 500;
}
</style>
