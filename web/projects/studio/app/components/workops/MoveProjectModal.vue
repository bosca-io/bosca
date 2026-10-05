<script setup lang="ts">
import gql from 'graphql-tag'

interface ProgramSummary {
  id: string
  key: string
  name: string
}

interface MovableProject {
  id: string
  name: string
  program: ProgramSummary | null
  version: number
}

interface ProgramOption extends ProgramSummary {
  archivedAt: string | null
}

const props = defineProps<{
  project: MovableProject
  accent?: string
}>()

const emit = defineEmits<{
  close: []
  moved: [project: MovableProject]
}>()

const { useAsyncQuery, mutation } = useGraphQL()
const destinationProgramId = ref('')
const moving = ref(false)
const error = ref('')

const { data: programsData, status: programsStatus } = useAsyncQuery<{
  workOps: { programs: { all: ProgramOption[] } }
}>('workops-move-project-programs', gql`
  query MoveProjectPrograms {
    workOps {
      programs {
        all {
          id
          key
          name
          archivedAt
        }
      }
    }
  }
`)

const programOptions = computed(() =>
  (programsData.value?.workOps?.programs?.all ?? [])
    .filter(program => !program.archivedAt && program.id !== props.project.program?.id)
    .map(program => ({
      value: program.id,
      label: `${program.key} — ${program.name}`,
    })),
)

const loadingPrograms = computed(() => programsStatus.value === 'pending')

async function moveProject() {
  if (!destinationProgramId.value) {
    error.value = 'Select a destination program.'
    return
  }
  moving.value = true
  error.value = ''
  try {
    const result = await mutation<{
      workOps: { projects: { move: MovableProject } }
    }>(gql`
      mutation MoveProject($id: UUID!, $programId: UUID!, $expectedVersion: Long!) {
        workOps {
          projects {
            move(id: $id, programId: $programId, expectedVersion: $expectedVersion) {
              id
              name
              program { id key name }
              version
            }
          }
        }
      }
    `, {
      id: props.project.id,
      programId: destinationProgramId.value,
      expectedVersion: props.project.version,
    })
    emit('moved', result.workOps.projects.move)
  } catch (cause: unknown) {
    error.value = cause instanceof Error ? cause.message : 'Failed to move project'
  } finally {
    moving.value = false
  }
}

function close() {
  if (!moving.value) emit('close')
}
</script>

<template>
  <Modal
    :title="`Move ${project.name}`"
    icon="arrowRight"
    :accent="accent"
    @close="close"
  >
    <div class="form-stack">
      <p class="move-description">
        Move this project from
        <strong>{{ project.program?.name ?? 'its current program' }}</strong>
        to another program. Its tasks, boards, and project permissions will stay with it.
      </p>
      <Select
        v-model="destinationProgramId"
        :options="programOptions"
        label="Destination program"
        placeholder="Select a program"
        :loading="loadingPrograms"
        :disabled="moving"
        :accent="accent"
      />
      <p v-if="!loadingPrograms && !programOptions.length" class="empty-message">
        No other active programs are available.
      </p>
      <p v-if="error" class="form-error">{{ error }}</p>
    </div>
    <template #footer>
      <Button :disabled="moving" @click="close">Cancel</Button>
      <Button
        primary
        :accent="accent"
        :disabled="moving || loadingPrograms || !destinationProgramId"
        @click="moveProject"
      >
        {{ moving ? 'Moving…' : 'Move Project' }}
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.move-description {
  margin: 0;
  color: var(--fg-2);
  font-size: 13px;
  line-height: 1.55;
}

.empty-message {
  margin: 0;
  color: var(--fg-3);
  font-size: 12px;
}

.form-error {
  margin: 0;
  color: var(--err);
  font-size: 12px;
}
</style>
