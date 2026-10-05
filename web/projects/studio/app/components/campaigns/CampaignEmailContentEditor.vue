<script setup lang="ts">
import gql from 'graphql-tag'
import JsonEditorVue from 'json-editor-vue'
import 'vanilla-jsoneditor/themes/jse-theme-dark.css'
import type { SelectOption } from '@bosca/ui'

interface HostedTemplate {
  key: string
  samplePayload: unknown | null
  supportsEmail: boolean
}

interface HostedProject {
  project: string
  activeVersion: string
  pinnedVersion: string | null
  templates: HostedTemplate[]
}

const props = withDefaults(defineProps<{
  modelValue: Record<string, unknown>
  disabled?: boolean
}>(), {
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

const hostedProjectsGql = gql`
  query CampaignBmlMessageHostedProjects {
    communications {
      bmlMessageHostedProjects {
        project
        activeVersion
        pinnedVersion
        templates { key samplePayload supportsEmail }
      }
    }
  }
`

const { useAsyncQuery } = useGraphQL()
const { data, status } = useAsyncQuery<{
  communications: { bmlMessageHostedProjects: HostedProject[] }
}>('campaign-bml-message-hosted-projects', hostedProjectsGql, {}, { server: false })

const hostedProjects = computed(() => (data.value?.communications?.bmlMessageHostedProjects ?? [])
  .map(project => ({ ...project, templates: project.templates.filter(template => template.supportsEmail) }))
  .filter(project => project.templates.length > 0))

function stringValue(key: string): string {
  const value = props.modelValue[key]
  return typeof value === 'string' ? value : ''
}

function emitValue(value: Record<string, unknown>) {
  emit('update:modelValue', value)
}

const project = computed({
  get: () => stringValue('project'),
  set: (value: string) => {
    const next: Record<string, unknown> = { ...props.modelValue, project: value }
    const selected = hostedProjects.value.find(item => item.project === value)
    if (!selected?.templates.some(template => template.key === stringValue('templateKey'))) {
      Reflect.deleteProperty(next, 'templateKey')
      Reflect.deleteProperty(next, 'payload')
    }
    emitValue(next)
  },
})

const selectedProject = computed(() => hostedProjects.value.find(item => item.project === project.value))

const templateKey = computed({
  get: () => stringValue('templateKey'),
  set: (value: string) => {
    const next: Record<string, unknown> = { ...props.modelValue, templateKey: value }
    const samplePayload = selectedProject.value?.templates.find(template => template.key === value)?.samplePayload
    if (samplePayload != null) next.payload = samplePayload
    else Reflect.deleteProperty(next, 'payload')
    emitValue(next)
  },
})

const payload = computed({
  get: () => props.modelValue.payload ?? {},
  set: (value: unknown) => emitValue({ ...props.modelValue, payload: value }),
})

const projectOptions = computed<SelectOption[]>(() => hostedProjects.value.map(item => ({
  value: item.project,
  label: item.pinnedVersion ? `${item.project} (pinned ${item.pinnedVersion})` : item.project,
})))

const templateOptions = computed<SelectOption[]>(() =>
  (selectedProject.value?.templates ?? []).map(template => ({ value: template.key, label: template.key })))

const versionLabel = computed(() => {
  if (!selectedProject.value) return ''
  return selectedProject.value.pinnedVersion
    ? `Sends use pinned version ${selectedProject.value.pinnedVersion}.`
    : `Sends use active version ${selectedProject.value.activeVersion}.`
})
</script>

<template>
  <div class="email-template-editor">
    <p v-if="status === 'pending'" class="editor-note">Loading BML message templates…</p>
    <p v-else-if="status === 'error'" class="editor-note editor-note--warning">
      BML message templates could not be loaded.
    </p>
    <p v-else-if="hostedProjects.length === 0" class="editor-note editor-note--warning">
      No hosted BML message templates support email.
    </p>

    <div class="field-row">
      <div class="field">
        <label class="field-label">BML Message Project</label>
        <Select
          v-model="project"
          :options="projectOptions"
          :disabled="disabled"
          placeholder="Select a project…" />
      </div>
      <div class="field">
        <label class="field-label">BML Message Template</label>
        <Select
          v-model="templateKey"
          :options="templateOptions"
          :disabled="disabled || !project"
          placeholder="Select a template…" />
      </div>
    </div>

    <p v-if="versionLabel" class="editor-note">{{ versionLabel }}</p>

    <div class="field">
      <label class="field-label">Template Payload</label>
      <JsonEditorVue
        v-model="payload"
        class="json-editor"
        mode="text"
        :main-menu-bar="false"
        :status-bar="false"
        :read-only="disabled" />
      <span class="field-hint">The selected template's sample payload is loaded as a starting point.</span>
    </div>
  </div>
</template>

<style scoped>
.email-template-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field-row {
  display: flex;
  gap: 12px;
}

.field {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-3);
}

.field-hint,
.editor-note {
  margin: 0;
  font-size: 11px;
  color: var(--fg-3);
}

.editor-note--warning {
  color: var(--warn, #ffb547);
}

.json-editor {
  height: 220px;
}
</style>
