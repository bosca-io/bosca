<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'

const { accent } = useCurrentSubsystem()
const { query: gqlQuery, useAsyncQuery } = useGraphQL()
const toast = useToast()

const hostedProjectsGql = gql`
  query BmlMessageHostedProjects {
    communications { bmlMessageHostedProjects { project activeVersion pinnedVersion templates { key samplePayload supportsEmail } } }
  }
`

const versionsGql = gql`
  query BmlMessageProjectVersions($project: String!) {
    communications { bmlMessageProjectVersions(project: $project) }
  }
`

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

const previewGql = gql`
  query EmailPreview($project: String!, $templateKey: String!, $payload: String, $version: String, $recipientName: String, $recipientEmail: String, $locale: String) {
    communications {
      emailPreview(project: $project, templateKey: $templateKey, payload: $payload, version: $version, recipientName: $recipientName, recipientEmail: $recipientEmail, locale: $locale) {
        project templateKey version subject html text
      }
    }
  }
`

// The platform language registry feeds the locale picker — previewing a translation renders
// exactly what a recipient with that bosca.profiles.locale setting would receive.
const languagesGql = gql`
  query PreviewLanguages {
    languages { all { tag name } }
  }
`

interface EmailPreview {
  project: string
  templateKey: string
  version: string
  subject: string
  html: string
  text: string
}

const project = ref('')
const templateKey = ref('')
const version = ref('')

const { data: hostedData } = useAsyncQuery<{
  communications: { bmlMessageHostedProjects: HostedProject[] }
}>('bml-message-hosted-projects', hostedProjectsGql, {}, { server: false })

const hostedProjects = computed(() => (hostedData.value?.communications?.bmlMessageHostedProjects ?? [])
  .map(project => ({ ...project, templates: project.templates.filter(template => template.supportsEmail) }))
  .filter(project => project.templates.length > 0))
const selectedProject = computed(() => hostedProjects.value.find(p => p.project === project.value))

const projectOptions = computed<SelectOption[]>(() => hostedProjects.value.map(p => ({
  value: p.project,
  label: p.pinnedVersion ? `${p.project} (pinned ${p.pinnedVersion})` : p.project,
})))

const templateOptions = computed<SelectOption[]>(() =>
  (selectedProject.value?.templates ?? []).map(t => ({ value: t.key, label: t.key })))

// The version list is a registry listing — fetch it lazily, only once a project is chosen.
const versionsProject = computed(() => project.value || undefined)
const { data: versionsData } = useAsyncQuery<{
  communications: { bmlMessageProjectVersions: string[] }
}>('bml-message-project-versions', versionsGql, { project: versionsProject }, { server: false })

const versionOptions = computed<SelectOption[]>(() => {
  const rendersByDefault = selectedProject.value?.pinnedVersion ?? selectedProject.value?.activeVersion
  const defaultLabel = rendersByDefault ? `Default (${rendersByDefault})` : 'Default (pinned / active)'
  const published = versionsData.value?.communications?.bmlMessageProjectVersions ?? []
  return [{ value: '', label: defaultLabel }, ...published.map(v => ({ value: v, label: v }))]
})

watch(project, () => {
  // A project switch invalidates the dependent selections.
  if (templateKey.value && !selectedProject.value?.templates?.some(t => t.key === templateKey.value)) templateKey.value = ''
  version.value = ''
})

watch(templateKey, () => {
  // Pre-fill the template's payload skeleton so the structure is visible up front —
  // but never clobber a payload the author already typed.
  const sample = selectedProject.value?.templates?.find(t => t.key === templateKey.value)?.samplePayload
  if (sample != null && !payload.value.trim()) payload.value = JSON.stringify(sample, null, 2)
})
const recipientName = ref('')
const recipientEmail = ref('')
const payload = ref('')
const locale = ref('')

const { data: languagesData } = useAsyncQuery<{
  languages: { all: Array<{ tag: string, name: string }> }
}>('preview-languages', languagesGql, {}, { server: false })

const localeOptions = computed<SelectOption[]>(() => [
  { value: '', label: 'Source language (default)' },
  ...(languagesData.value?.languages?.all ?? []).map(l => ({ value: l.tag, label: `${l.name} (${l.tag})` })),
])

const rendering = ref(false)
const preview = ref<EmailPreview | null>(null)
const renderError = ref('')

const OUTPUT_TABS = ['HTML', 'Text'] as const
const outputTab = ref<(typeof OUTPUT_TABS)[number]>('HTML')

const hasReference = computed(() =>
  project.value.trim().length > 0 && templateKey.value.trim().length > 0)

async function render() {
  if (!hasReference.value) {
    toast.error('Select a project and template')
    return
  }
  if (payload.value.trim()) {
    try {
      JSON.parse(payload.value)
    } catch (e: unknown) {
      toast.error(`Payload is not valid JSON: ${e instanceof Error ? e.message : e}`)
      return
    }
  }
  rendering.value = true
  renderError.value = ''
  try {
    const data = await gqlQuery<{ communications: { emailPreview: EmailPreview } }>(previewGql, {
      project: project.value.trim(),
      templateKey: templateKey.value.trim(),
      payload: payload.value.trim() || null,
      version: version.value.trim() || null,
      recipientName: recipientName.value.trim() || null,
      recipientEmail: recipientEmail.value.trim() || null,
      locale: locale.value.trim() || null,
    })
    preview.value = data.communications.emailPreview
  } catch (e: unknown) {
    preview.value = null
    renderError.value = e instanceof Error ? e.message : 'Render failed'
  } finally {
    rendering.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Messaging', 'Email Preview')"
        title="Email Preview"
        subtitle="Render a BML message template's email channel exactly as a send would — the registry pin applies unless a version is given.">
        <template #actions>
          <Button
            primary
            icon="eye"
            size="sm"
            :accent="accent"
            :loading="rendering"
            @click="render">Render</Button>
        </template>
      </PageHeader>
    </template>

    <SectionCard title="Template" padded>
      <div class="form-grid">
        <div class="form-field">
          <label class="field-label">Project</label>
          <Select
            v-model="project"
            :options="projectOptions"
            :accent="accent"
            placeholder="Select a project…" />
        </div>
        <div class="form-field">
          <label class="field-label">Template</label>
          <Select
            v-model="templateKey"
            :options="templateOptions"
            :accent="accent"
            :disabled="!project"
            placeholder="Select a template…" />
        </div>
        <div class="form-field">
          <label class="field-label">Version</label>
          <Select
            v-model="version"
            :options="versionOptions"
            :accent="accent"
            :disabled="!project"
            placeholder="Default (pinned / active)" />
        </div>
        <div class="form-field">
          <label class="field-label">Locale</label>
          <Select
            v-model="locale"
            :options="localeOptions"
            :accent="accent"
            placeholder="Source language (default)" />
        </div>
        <div class="form-field">
          <label class="field-label">Sample Recipient Name</label>
          <input
            v-model="recipientName"
            type="text"
            class="modal-input"
            placeholder="e.g. Sarah" >
        </div>
        <div class="form-field">
          <label class="field-label">Sample Recipient Email</label>
          <input
            v-model="recipientEmail"
            type="text"
            class="modal-input"
            placeholder="e.g. sarah@example.com" >
        </div>
        <div class="form-field span-2">
          <label class="field-label">Payload (JSON)</label>
          <textarea
            v-model="payload"
            class="modal-input mono payload"
            rows="5"
            placeholder='{ "courseName": "Exploring Truth" }' />
        </div>
      </div>
    </SectionCard>

    <SectionCard v-if="renderError" padded>
      <p class="render-error">{{ renderError }}</p>
    </SectionCard>

    <SectionCard v-if="preview" padded>
      <div class="preview-meta">
        <div class="meta-item">
          <span class="field-label">Subject</span>
          <span class="subject">{{ preview.subject }}</span>
        </div>
        <div class="meta-item">
          <span class="field-label">Template</span>
          <span class="mono">{{ preview.project }}/{{ preview.templateKey }}</span>
        </div>
        <div class="meta-item">
          <span class="field-label">Rendered Version</span>
          <span class="mono">{{ preview.version }}</span>
        </div>
      </div>

      <Tabs v-model="outputTab" :tabs="[...OUTPUT_TABS]" :accent="accent" />

      <!-- sandboxed: preview HTML is authored content, never granted script access -->
      <iframe
        v-if="outputTab === 'HTML'"
        class="preview-frame"
        sandbox=""
        :srcdoc="preview.html"
        title="Email HTML preview" />
      <pre v-else class="preview-text">{{ preview.text }}</pre>
    </SectionCard>
  </PageShell>
</template>

<style scoped>
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.form-field { display: flex; flex-direction: column; gap: 4px; }
.span-2 { grid-column: span 2; }
.field-label { font-size: 12px; color: var(--fg-3); }
.mono { font-family: var(--font-mono); font-size: 12px; }
.modal-input {
  width: 100%; font-size: 13px; padding: 8px 12px; border-radius: var(--r-xs);
  background: var(--bg-0); color: var(--fg-1); border: 1px solid var(--line); outline: none;
}
.modal-input:focus { border-color: color-mix(in oklch, var(--fg-3) 30%, transparent); }
.payload { resize: vertical; min-height: 96px; }
.render-error { font-size: 13px; color: var(--danger, #f26d6d); white-space: pre-wrap; }
.preview-meta { display: flex; gap: 32px; flex-wrap: wrap; margin-bottom: 16px; }
.meta-item { display: flex; flex-direction: column; gap: 4px; }
.subject { font-size: 14px; font-weight: 600; color: var(--fg-0); }
.preview-frame {
  width: 100%; min-height: 560px; margin-top: 16px; border: 1px solid var(--line);
  border-radius: var(--r-xs); background: #fff;
}
.preview-text {
  margin-top: 16px; padding: 16px; border: 1px solid var(--line); border-radius: var(--r-xs);
  background: var(--bg-0); color: var(--fg-1); font-family: var(--font-mono); font-size: 12px;
  line-height: 1.6; white-space: pre-wrap; overflow-x: auto;
}
</style>
