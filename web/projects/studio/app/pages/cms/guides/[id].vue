<script setup lang="ts">
import gql from 'graphql-tag'
import type { Metadata, Profile } from '~/types/graphql'
import type { OverflowMenuItem } from '@bosca/ui'
import { getWorkflowState } from '~/utils/workflowStatus'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation, useSubscription } = useGraphQL()
const { isAdmin, hasGroup, load: loadPersonas } = usePersonas()
const { commentsEnabled, load: loadFeatures } = useServerFeatures()
const canModerate = computed(() => commentsEnabled.value && (isAdmin.value || hasGroup('sa')))
const toast = useToast()

// ── Guide query: fetches guide structure + full metadata for intro page ──
const guideGql = gql`
  query GetGuideDetail($id: UUID!) {
    profiles { current { id name } }
    content {
      metadata(id: $id) {
        __typename id version name slug ready locked languageTag
        parentId
        attributes
        content { type urls { upload { url headers { name value } } } }
        parentCollections(offset: 0, limit: 1000) { id slug name attributes }
        variants { id languageTag name }
        relationships {
          __typename
          relationship attributes
          metadata { id name slug languageTag type attributes content { type } }
        }
        document {
          title content
          template {
            id version
            documentTemplate {
              content schema
              attributes {
                configuration description key list location name supplementaryKey type ui
                workflows { autoRun }
                tools { id name description query resultPath }
              }
              containers { id name description type filters tools { id name description query resultPath } }
            }
          }
        }
        guide {
          rrule type
          template {
            id version name
            guideTemplate { steps { id metadata { id name } modules { id metadata { id name } } } }
            documentTemplate {
              content schema
              attributes {
                configuration description key list location name supplementaryKey type ui
                workflows { autoRun }
                tools { id name description query resultPath }
              }
              containers { id name description type filters tools { id name description query resultPath } }
            }
          }
          steps { id date metadata { id name } modules { id metadata { id name } } }
        }
        workflow {
          state stateValid pending running
          activeJobs { jobName displayName jobId status created complete success delayedUntil }
        }
      }
    }
  }
`

// ── Step query: fetches full metadata for the selected step ──
const stepGql = gql`
  query GetGuideStepDocument($id: UUID!) {
    profiles { current { id name } }
    content {
      metadata(id: $id) {
        __typename id version name slug ready locked languageTag
        attributes
        content { type urls { upload { url headers { name value } } } }
        parentCollections(offset: 0, limit: 1000) { id slug name attributes }
        variants { id languageTag name }
        relationships {
          __typename
          relationship attributes
          metadata { id name slug languageTag type attributes content { type } }
        }
        document {
          title content
          template {
            id version
            documentTemplate {
              content schema
              attributes {
                configuration description key list location name supplementaryKey type ui
                workflows { autoRun }
                tools { id name description query resultPath }
              }
              containers { id name description type filters tools { id name description query resultPath } }
            }
          }
        }
        workflow { state stateValid pending running }
      }
    }
  }
`

const addStepGql = gql`
  mutation AddGuideStep($metadataId: UUID!, $metadataVersion: Int!, $sort: Int!, $templateStepId: Long!) {
    content { metadata { addGuideStep(metadataId: $metadataId, metadataVersion: $metadataVersion, sort: $sort, templateStepId: $templateStepId) { metadata { id } } } }
  }
`
const deleteStepGql = gql`
  mutation DeleteGuideStep($metadataId: UUID!, $metadataVersion: Int!, $stepId: Long!) {
    content { metadata { deleteGuideStep(metadataId: $metadataId, metadataVersion: $metadataVersion, stepId: $stepId) } }
  }
`
const setReadyGql = gql`
  mutation SetStepReady($id: UUID!) { content { metadata { setMetadataReady(id: $id) } } }
`
const transitionGql = gql`
  mutation BeginGuideTransition($id: UUID!, $version: Int!, $state: String!, $status: String!) {
    content { transitions { beginTransition(request: { metadataId: $id, version: $version, stateId: $state, status: $status }) } }
  }
`
const reorderStepsGql = gql`
  mutation ReorderGuideSteps($metadataId: UUID!, $metadataVersion: Int!, $stepIds: [Long!]!) {
    content { metadata { guide(metadataId: $metadataId, metadataVersion: $metadataVersion) { reorderSteps(stepIds: $stepIds) } } }
  }
`
const addStepModuleGql = gql`
  mutation AddGuideStepModule($metadataId: UUID!, $metadataVersion: Int!, $sort: Int!, $stepId: Long!, $templateModuleId: Long!) {
    content { metadata { addGuideStepModule(metadataId: $metadataId, metadataVersion: $metadataVersion, sort: $sort, stepId: $stepId, templateModuleId: $templateModuleId) { id } } }
  }
`
const deleteStepModuleGql = gql`
  mutation DeleteGuideStepModule($metadataId: UUID!, $metadataVersion: Int!, $stepId: Long!, $moduleId: Long!) {
    content { metadata { deleteGuideStepModule(metadataId: $metadataId, metadataVersion: $metadataVersion, stepId: $stepId, moduleId: $moduleId) } }
  }
`
const reorderStepModulesGql = gql`
  mutation ReorderGuideStepModules($metadataId: UUID!, $metadataVersion: Int!, $stepId: Long!, $moduleIds: [Long!]!) {
    content { metadata { guide(metadataId: $metadataId, metadataVersion: $metadataVersion) { reorderModules(stepId: $stepId, moduleIds: $moduleIds) } } }
  }
`
const setStartDateGql = gql`
  mutation SetGuideStartDate($date: DateTime!, $metadataId: UUID!, $metadataVersion: Int!) {
    content { metadata { setGuideStartDate(date: $date, metadataId: $metadataId, metadataVersion: $metadataVersion) { id } } }
  }
`
const setGuideTypeGql = gql`
  mutation SetGuideTypeInstance($metadataId: UUID!, $metadataVersion: Int!, $type: GuideType!) {
    content { metadata { guide(metadataId: $metadataId, metadataVersion: $metadataVersion) { setType(guideType: $type) } } }
  }
`
const setGuideRruleGql = gql`
  mutation SetGuideRruleInstance($metadataId: UUID!, $metadataVersion: Int!, $rrule: String!) {
    content { metadata { guide(metadataId: $metadataId, metadataVersion: $metadataVersion) { setRrule(rrule: $rrule) } } }
  }
`
const deleteGql = gql`
  mutation DeleteGuide($id: UUID!) { content { metadata { delete(metadataId: $id) } } }
`

// ── Data ─────────────────────────────────────────────────────────────────
const guideId = route.params.id as string
const stepId = ref(guideId)

const { data: guideResult, refresh } = await useAsyncQuery<{
  profiles: { current: Profile[] }
  content: { metadata: Metadata }
}>('guide-metadata', guideGql, { id: guideId })

const { data: stepResult, status: stepStatus, refresh: refreshStep } = await useAsyncQuery<{
  profiles: { current: Profile[] }
  content: { metadata: Metadata }
}>('guide-step', stepGql, { id: stepId })

const guideMetadata = computed(() => guideResult.value?.content?.metadata as Metadata | undefined)
const profile = computed<Profile>(() => {
  const c = guideResult.value?.profiles?.current
  return (c as unknown as Profile[])?.[0] ?? ({} as Profile)
})

const guide = computed(() => (guideMetadata.value as Record<string, unknown> | undefined)?.guide as Record<string, unknown> | null ?? null)
const steps = computed<Array<{ id: string; metadata: Record<string, unknown>; [key: string]: unknown }>>(() => (guide.value?.steps as Array<{ id: string; metadata: Record<string, unknown>; [key: string]: unknown }>) ?? [])
const guideType = computed(() => (guide.value?.type as string) ?? 'LINEAR')
const isCalendar = computed(() => guideType.value === 'CALENDAR' || guideType.value === 'CALENDAR_PROGRESS')
const templateSteps = computed(() => {
  const template = guide.value?.template as Record<string, unknown> | undefined
  const guideTemplate = template?.guideTemplate as Record<string, unknown> | undefined
  return (guideTemplate?.steps as Array<{ id: string }>) ?? []
})

// ── Current page / active metadata ───────────────────────────────────────
const currentPage = ref(1)
const guideStep = ref<Record<string, unknown> | null>(null)
const contentEditorRef = ref<{ documentName?: string; hasUnsavedChanges?: boolean; saving?: boolean; save?: () => Promise<boolean> } | null>(null)
const navPagesRef = ref<HTMLElement | null>(null)

// For intro: guide metadata with document.template patched from guide.template
function getIntroMetadata(): Metadata | null {
  const m = guideMetadata.value
  if (!m) return null
  if (m.document && !m.document.template && m.guide?.template) {
    return { ...m, document: { ...m.document, template: m.guide.template } } as Metadata
  }
  return m
}

// The metadata currently being edited
const activeMetadata = computed<Metadata | null>(() => {
  if (currentPage.value === 1) return getIntroMetadata()
  return (stepResult.value?.content?.metadata as Metadata) ?? null
})

const activeWorkflow = computed(() => (activeMetadata.value as Metadata & { workflow?: Record<string, unknown> | null })?.workflow ?? null)
const wfState = computed(() => getWorkflowState(activeWorkflow.value))
const canPublish = computed(() => wfState.value !== 'published' && !activeWorkflow.value?.pending)
const canUnpublish = computed(() => wfState.value === 'published' && !activeWorkflow.value?.pending)

const documentName = computed(() => {
  return contentEditorRef.value?.documentName ?? activeMetadata.value?.name ?? guideMetadata.value?.name ?? ''
})

const hasUnsavedChanges = computed(() => contentEditorRef.value?.hasUnsavedChanges ?? false)
const saving = computed(() => contentEditorRef.value?.saving ?? false)

// ── Navigation ───────────────────────────────────────────────────────────
watch(currentPage, (page) => {
  if (page === 1) {
    stepId.value = guideId
    guideStep.value = null
  } else {
    const guideSteps = guide.value?.steps as Array<{ id: string; metadata?: { id: string; name?: string }; [key: string]: unknown }> | undefined
    const step = guideSteps?.[page - 2]
    if (!step) return
    stepId.value = step.metadata?.id as string
    guideStep.value = step
    refreshStep()
  }
})

// When the step row overflows and scrolls, keep the active step button visible.
watch(currentPage, async () => {
  await nextTick()
  navPagesRef.value
    ?.querySelector<HTMLElement>('.guide-nav-btn.active')
    ?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' })
})

// ── Step strip scrolling ─────────────────────────────────────────────────
// The strip's scrollbar is hidden, so scrolling affordances are: trackpad
// swipes, vertical wheel translated to horizontal, and edge chevrons that
// appear while there is overflow in that direction.
const canScrollLeft = ref(false)
const canScrollRight = ref(false)
const hasStepOverflow = ref(false)

function updateStepScrollState() {
  const el = navPagesRef.value
  if (!el) {
    canScrollLeft.value = false
    canScrollRight.value = false
    hasStepOverflow.value = false
    return
  }
  hasStepOverflow.value = el.scrollWidth > el.clientWidth + 1
  canScrollLeft.value = el.scrollLeft > 1
  canScrollRight.value = el.scrollLeft + el.clientWidth < el.scrollWidth - 1
}

function scrollSteps(direction: 1 | -1) {
  const el = navPagesRef.value
  if (!el) return
  el.scrollBy({ left: direction * Math.max(160, el.clientWidth * 0.6), behavior: 'smooth' })
}

function onStepsWheel(e: WheelEvent) {
  const el = navPagesRef.value
  if (!el || el.scrollWidth <= el.clientWidth) return
  if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
    el.scrollLeft += e.deltaY
    e.preventDefault()
  }
}

onMounted(() => {
  updateStepScrollState()
  window.addEventListener('resize', updateStepScrollState)
  loadPersonas(); loadFeatures()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateStepScrollState)
})

watch([steps, navPagesRef], async () => {
  await nextTick()
  updateStepScrollState()
})

const totalPages = computed(() => steps.value.length + 1)

function pageLabel(page: number): string {
  if (page === 1) return 'Intro'
  if (isCalendar.value) {
    const step = steps.value[page - 2]
    if (step?.date) {
      const d = new Date(step.date as string | number)
      return `${d.getUTCMonth() + 1}/${d.getUTCDate()}`
    }
  }
  return String(page - 1)
}

// ── Subscription ─────────────────────────────────────────────────────────
const subGql = gql`subscription GuideChanges { metadata { id type version } }`

async function refreshAll() {
  await refresh()
  await refreshStep()
}

let refreshTimer: ReturnType<typeof setTimeout> | undefined
useSubscription(subGql, {}, (event: unknown) => {
  const id = (event as { metadata?: { id?: string } })?.metadata?.id
  if (id === guideId || id === stepId.value || id === activeMetadata.value?.id) {
    clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => refreshAll(), 1000)
  }
})

// ── Actions ──────────────────────────────────────────────────────────────
async function onSave() {
  const ok = await contentEditorRef.value?.save?.()
  if (ok) {
    toast.success('Saved')
  } else {
    toast.error('Failed to save')
  }
}

async function onReady() {
  const m = activeMetadata.value
  if (!m) return
  try {
    if (hasUnsavedChanges.value) await contentEditorRef.value?.save?.()
    await gqlMutation(setReadyGql, { id: m.id })
    toast.success('Marked ready')
    await refreshAll()
  } catch { toast.error('Failed to mark ready') }
}

async function onPublish() {
  const m = activeMetadata.value
  if (!m) return
  try {
    if (hasUnsavedChanges.value) await contentEditorRef.value?.save?.()
    await gqlMutation(transitionGql, { id: m.id, version: m.version, state: 'published', status: 'Admin Published' })
    toast.success('Published')
    await refreshAll()
  } catch { toast.error('Failed to publish') }
}

async function onUnpublish() {
  const m = activeMetadata.value
  if (!m) return
  try {
    await gqlMutation(transitionGql, { id: m.id, version: m.version, state: 'draft', status: 'Admin Unpublished' })
    toast.success('Unpublished')
    await refreshAll()
  } catch { toast.error('Failed to unpublish') }
}

async function onAddStep() {
  if (hasUnsavedChanges.value) { toast.warn('Save changes first'); return }
  if (!templateSteps.value.length || !guideMetadata.value) return
  try {
    const ts = templateSteps.value[0]!
    const result = await gqlMutation<{ content: { metadata: { addGuideStep: { metadata: { id: string } } } } }>(addStepGql, {
      metadataId: guideId, metadataVersion: guideMetadata.value.version,
      sort: currentPage.value - 1, templateStepId: ts.id,
    })
    const newId = result?.content?.metadata?.addGuideStep?.metadata?.id
    if (newId) await gqlMutation(setReadyGql, { id: newId })
    await refreshAll()
    currentPage.value++
    toast.success('Step added')
  } catch { toast.error('Failed to add step') }
}

function onDeleteStepClick() {
  if (hasUnsavedChanges.value) { toast.warn('Save changes first'); return }
  if (!guideStep.value || !guideMetadata.value) return
  deleteStepModalOpen.value = true
}

async function onConfirmDeleteStep() {
  if (!guideStep.value || !guideMetadata.value) return
  try {
    await gqlMutation(deleteStepGql, {
      metadataId: guideId, metadataVersion: guideMetadata.value.version, stepId: guideStep.value.id,
    })
    deleteStepModalOpen.value = false
    currentPage.value = Math.max(1, currentPage.value - 1)
    await refreshAll()
    toast.success('Step deleted')
  } catch { toast.error('Failed to delete step') }
}

const deleteStepModalOpen = ref(false)
const deleteModalOpen = ref(false)
const progressModalOpen = ref(false)
const deleting = ref(false)

async function onConfirmDelete() {
  deleting.value = true
  try {
    await gqlMutation(deleteGql, { id: guideId })
    toast.success('Guide deleted')
    deleteModalOpen.value = false
    router.push('/cms/guides')
  } catch { toast.error('Failed to delete') }
  finally { deleting.value = false }
}

const reorderModalOpen = ref(false)
const reordering = ref(false)

// ── Start date ───────────────────────────────────────────────────────────
// The guide's start date lives in its RRULE as DTSTART; calendar guides
// schedule step dates from it.
const startDateModalOpen = ref(false)
const startDateValue = ref('')
const startDateSaving = ref(false)

function openStartDateModal() {
  const rrule = (guide.value?.rrule as string | null) ?? ''
  const match = rrule.match(/DTSTART:(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z/)
  startDateValue.value = match
    ? `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}`
    : ''
  startDateModalOpen.value = true
}

async function onSaveStartDate() {
  if (!startDateValue.value || !guideMetadata.value || startDateSaving.value) return
  startDateSaving.value = true
  try {
    await gqlMutation(setStartDateGql, {
      date: new Date(startDateValue.value).toISOString(),
      metadataId: guideId,
      metadataVersion: guideMetadata.value.version,
    })
    startDateModalOpen.value = false
    toast.success('Start date updated')
    await refreshAll()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update start date')
  } finally {
    startDateSaving.value = false
  }
}

// ── Guide settings (type + recurrence) ───────────────────────────────────
const settingsModalOpen = ref(false)
const settingsSaving = ref(false)

async function onSaveSettings(payload: { type: string; rrule: string | null }) {
  if (!guideMetadata.value) return
  settingsSaving.value = true
  try {
    const metadataVersion = guideMetadata.value.version
    if (payload.type && payload.type !== guideType.value) {
      await gqlMutation(setGuideTypeGql, { metadataId: guideId, metadataVersion, type: payload.type })
    }
    const currentRrule = (guide.value?.rrule as string | null) ?? null
    if (payload.rrule && payload.rrule !== currentRrule) {
      await gqlMutation(setGuideRruleGql, { metadataId: guideId, metadataVersion, rrule: payload.rrule })
    }
    settingsModalOpen.value = false
    toast.success('Guide settings saved')
    await refreshAll()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save settings')
  } finally {
    settingsSaving.value = false
  }
}

// ── Step modules ─────────────────────────────────────────────────────────
// The active step, re-resolved from the latest guide data (guideStep is only
// set on page change, so it goes stale after refreshAll).
const activeStep = computed(() => {
  if (currentPage.value === 1) return null
  return steps.value[currentPage.value - 2] ?? null
})

// All modules defined across the guide template's steps, offered when adding
// a module. Template module ids are globally unique, so a flat list is safe.
const templateModules = computed<Array<{ id: number; name: string }>>(() => {
  const seen = new Set<number>()
  const result: Array<{ id: number; name: string }> = []
  for (const ts of templateSteps.value as Array<{ id: string; modules?: Array<{ id: number; metadata?: { name?: string } | null }> }>) {
    for (const m of ts.modules ?? []) {
      if (seen.has(m.id)) continue
      seen.add(m.id)
      result.push({ id: m.id, name: m.metadata?.name ?? `Module ${m.id}` })
    }
  }
  return result
})

const modulesModalOpen = ref(false)
const modulesSaving = ref(false)

const activeStepModules = computed<Array<{ id: number; metadata: { id: string; name: string } | null }>>(() =>
  (activeStep.value?.modules as Array<{ id: number; metadata: { id: string; name: string } | null }>) ?? [])

async function onAddModule(templateModuleId: number) {
  const step = activeStep.value
  if (!step || !guideMetadata.value) return
  modulesSaving.value = true
  try {
    await gqlMutation(addStepModuleGql, {
      metadataId: guideId,
      metadataVersion: guideMetadata.value.version,
      sort: activeStepModules.value.length,
      stepId: step.id,
      templateModuleId,
    })
    await refreshAll()
    toast.success('Module added')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to add module')
  } finally {
    modulesSaving.value = false
  }
}

async function onDeleteModule(moduleId: number) {
  const step = activeStep.value
  if (!step || !guideMetadata.value) return
  modulesSaving.value = true
  try {
    await gqlMutation(deleteStepModuleGql, {
      metadataId: guideId,
      metadataVersion: guideMetadata.value.version,
      stepId: step.id,
      moduleId,
    })
    await refreshAll()
    toast.success('Module deleted')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to delete module')
  } finally {
    modulesSaving.value = false
  }
}

async function onReorderModules(moduleIds: number[]) {
  const step = activeStep.value
  if (!step || !guideMetadata.value) return
  modulesSaving.value = true
  try {
    await gqlMutation(reorderStepModulesGql, {
      metadataId: guideId,
      metadataVersion: guideMetadata.value.version,
      stepId: step.id,
      moduleIds,
    })
    await refreshAll()
    toast.success('Module order saved')
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to reorder modules')
  } finally {
    modulesSaving.value = false
  }
}

async function onReorderSteps(stepIds: number[]) {
  if (!guideMetadata.value) return
  reordering.value = true
  try {
    const currentStepId = guideStep.value?.id
    await gqlMutation(reorderStepsGql, {
      metadataId: guideId,
      metadataVersion: guideMetadata.value.version,
      stepIds,
    })
    reorderModalOpen.value = false
    toast.success('Steps reordered')
    await refreshAll()
    if (currentStepId != null) {
      const newIndex = stepIds.indexOf(Number(currentStepId))
      if (newIndex >= 0) currentPage.value = newIndex + 2
    }
  } catch {
    toast.error('Failed to reorder steps')
  } finally {
    reordering.value = false
  }
}

const overflowItems = computed<OverflowMenuItem[]>(() => {
  const items: OverflowMenuItem[] = []
  if (canModerate.value) {
    items.push({ id: 'comments', label: 'Comments', icon: 'message-square' })
  }
  items.push(
    { id: 'view-metadata', label: 'View Metadata', icon: 'database' },
    { id: 'copy-id', label: 'Copy ID', icon: 'copy' },
  )
  if (canUnpublish.value) {
    items.push({ id: 'unpublish', label: 'Unpublish', icon: 'archive' })
  }
  items.push({ id: 'sep', label: '', separator: true })
  items.push({ id: 'delete', label: 'Delete Guide', icon: 'trash', danger: true })
  return items
})

async function onOverflowAction(id: string) {
  if (id === 'view-metadata') navigateTo(`/cms/metadata/${activeMetadata.value?.id ?? guideId}`)
  else if (id === 'comments') navigateTo(`/cms/comments/${activeMetadata.value?.id ?? guideId}`)
  else if (id === 'copy-id') { await navigator.clipboard.writeText(activeMetadata.value?.id ?? guideId); toast.success('Copied') }
  else if (id === 'unpublish') onUnpublish()
  else if (id === 'delete') deleteModalOpen.value = true
}
</script>

<template>
  <PageShell class="guide-shell">
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('CMS', 'Guides', guideMetadata?.name ?? '…')"
        :title="documentName || 'Loading…'"
      >
        <template #actions>
          <Button
            v-if="isAdmin || hasGroup('sa')"
            size="sm"
            icon="segment"
            @click="progressModalOpen = true">Progress</Button>
          <Button
            size="sm"
            icon="save"
            primary
            :accent="accent"
            :disabled="saving"
            @click="onSave">Save</Button>
          <Button
            v-if="activeMetadata && !activeMetadata.ready"
            size="sm"
            icon="check"
            :accent="accent"
            @click="onReady">Ready</Button>
          <Button
            v-if="canPublish"
            size="sm"
            icon="globe"
            primary
            :accent="accent"
            :disabled="!activeMetadata?.ready"
            @click="onPublish">Publish</Button>
          <MetadataLanguageMenu
            v-if="guideMetadata"
            :metadata-id="guideMetadata.id"
            :parent-id="(guideMetadata as Metadata & { parentId?: string | null }).parentId"
            :metadata-version="guideMetadata.version"
            :language-tag="guideMetadata.languageTag"
            :variants="((guideMetadata as Metadata & { variants?: Array<{ id: string; languageTag: string; name: string }> }).variants) ?? []"
            url-prefix="/cms/guides/"
            :accent="accent"
          />
          <OverflowMenu :items="overflowItems" @select="onOverflowAction">
            <template #default="{ toggle }">
              <Button size="sm" icon="more" @click="toggle">More</Button>
            </template>
          </OverflowMenu>
        </template>
      </PageHeader>
    </template>

    <!-- Guide navigation -->
    <div v-if="guideMetadata" class="guide-nav">
      <button
        v-if="hasStepOverflow"
        class="guide-nav-action"
        title="Scroll steps"
        :disabled="!canScrollLeft"
        @click="scrollSteps(-1)">
        <Icon name="chevronLeft" :size="14" />
      </button>
      <div
        ref="navPagesRef"
        class="guide-nav-pages"
        @scroll.passive="updateStepScrollState"
        @wheel="onStepsWheel"
      >
        <button
          v-for="p in totalPages"
          :key="p"
          class="guide-nav-btn"
          :class="{ active: p === currentPage }"
          @click="currentPage = p"
        >{{ pageLabel(p) }}</button>
      </div>
      <button
        v-if="hasStepOverflow"
        class="guide-nav-action"
        title="Scroll steps"
        :disabled="!canScrollRight"
        @click="scrollSteps(1)">
        <Icon name="chevron" :size="14" />
      </button>
      <button
        v-if="!guideMetadata.locked"
        class="guide-nav-action"
        title="Guide settings"
        :disabled="hasUnsavedChanges"
        @click="settingsModalOpen = true">
        <Icon name="settings" :size="14" />
      </button>
      <button
        v-if="guide?.rrule || isCalendar"
        class="guide-nav-action"
        title="Guide start date"
        :disabled="hasUnsavedChanges"
        @click="openStartDateModal">
        <Icon name="clock" :size="14" />
      </button>
      <button
        v-if="steps.length > 1"
        class="guide-nav-action"
        title="Reorder steps"
        :disabled="hasUnsavedChanges"
        @click="reorderModalOpen = true">
        <Icon name="list" :size="14" />
      </button>
      <button
        class="guide-nav-action"
        title="Add step"
        :disabled="hasUnsavedChanges"
        @click="onAddStep">
        <Icon name="plus" :size="14" />
      </button>
      <button
        v-if="activeStep && !guideMetadata.locked"
        class="guide-nav-action"
        title="Step modules"
        :disabled="hasUnsavedChanges"
        @click="modulesModalOpen = true">
        <Icon name="layers" :size="14" />
      </button>
      <button
        v-if="guideStep && !guideMetadata.locked"
        class="guide-nav-action guide-nav-action--danger"
        title="Delete step"
        :disabled="hasUnsavedChanges"
        @click="onDeleteStepClick">
        <Icon name="x" :size="14" />
      </button>
      <span class="guide-nav-loading" :class="{ visible: stepStatus === 'pending' }"><Icon name="spinner" :size="14" /></span>
    </div>

    <!-- Content editor — keyed by metadata ID so it fully remounts on navigation -->
    <GuideContentEditor
      v-if="activeMetadata"
      ref="contentEditorRef"
      :key="activeMetadata.id"
      :metadata="activeMetadata"
      :profile="profile"
      :accent="accent"
      :refresh-metadata="refreshAll"
    />

    <div v-else class="loading-state">Loading…</div>

    <ConfirmModal
      v-if="deleteStepModalOpen"
      title="Delete Step"
      :subtitle="`Delete '${(guideStep?.metadata as Record<string, unknown> | undefined)?.name ?? 'this step'}'? The step content will not be deleted.`"
      confirm-label="Delete Step"
      @close="deleteStepModalOpen = false"
      @confirm="onConfirmDeleteStep"
    />

    <ConfirmModal
      v-if="deleteModalOpen"
      title="Delete Guide"
      :subtitle="`Delete '${guideMetadata?.name}'? This cannot be undone.`"
      :loading="deleting"
      @close="deleteModalOpen = false"
      @confirm="onConfirmDelete"
    />

    <GuideReorderModal
      v-if="reorderModalOpen"
      :steps="steps.map((s) => ({ id: Number(s.id), metadata: s.metadata as { id: string; name: string } | null }))"
      :loading="reordering"
      @close="reorderModalOpen = false"
      @save="onReorderSteps"
    />

    <Modal
      v-if="startDateModalOpen"
      title="Guide Start Date"
      subtitle="Calendar guides schedule step dates from this date"
      icon="clock"
      width="380px"
      @close="startDateModalOpen = false"
    >
      <TextInput
        v-model="startDateValue"
        label="Start Date"
        type="datetime-local"
        size="sm" />
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="startDateModalOpen = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="!startDateValue || startDateSaving"
          @click="onSaveStartDate">
          {{ startDateSaving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Modal>

    <GuideSettingsModal
      v-if="settingsModalOpen && guide"
      :type="guideType"
      :rrule="(guide.rrule as string | null) ?? null"
      :accent="accent"
      :loading="settingsSaving"
      @close="settingsModalOpen = false"
      @save="onSaveSettings"
    />

    <GuideStepModulesModal
      v-if="modulesModalOpen && activeStep"
      :step-name="((activeStep.metadata as Record<string, unknown> | undefined)?.name as string) ?? 'Step'"
      :modules="activeStepModules"
      :template-modules="templateModules"
      :loading="modulesSaving"
      @close="modulesModalOpen = false"
      @add="onAddModule"
      @delete="onDeleteModule"
      @save="onReorderModules"
    />

    <GuideProgressModal
      v-if="progressModalOpen && guideMetadata"
      :guide-id="guideId"
      :guide-name="guideMetadata.name"
      :accent="accent"
      @close="progressModalOpen = false"
    />
  </PageShell>
</template>

<style scoped>
.guide-shell :deep(.page-content) {
  padding: 0;
  gap: 0;
  display: flex;
  flex-direction: column;
}

.guide-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  flex-shrink: 0;
}

.guide-nav-pages {
  display: flex;
  gap: 0;
  align-items: center;
  /* Let the row shrink within the flex nav so it scrolls instead of pushing the
     action buttons off-screen when there are many steps. */
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  scroll-behavior: smooth;
  /* No visible scrollbar — wheel/trackpad scrolling still works and the active
     step auto-scrolls into view on navigation. */
  scrollbar-width: none;
}

.guide-nav-pages::-webkit-scrollbar {
  display: none;
}

.guide-nav-btn {
  min-width: 38px;
  height: 32px;
  padding: 0 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-2);
  transition: all 0.15s;
  border: 1px solid var(--line);
  background: var(--bg-1);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-left: -1px;
  /* Don't let the flex row compress buttons when it overflows — the row
     scrolls instead. */
  flex: 0 0 auto;
  white-space: nowrap;
  /* The z-index rules below need a positioning context; without it the -1px
     border collapse paints neighbors over the highlighted button's border,
     making the active fill look lopsided. */
  position: relative;
}

.guide-nav-btn:first-child { border-radius: var(--r-sm) 0 0 var(--r-sm); margin-left: 0; }
.guide-nav-btn:last-child { border-radius: 0 var(--r-sm) var(--r-sm) 0; }
.guide-nav-btn:only-child { border-radius: var(--r-sm); }

.guide-nav-btn:hover:not(:disabled):not(.active) { background: var(--bg-2); color: var(--fg-0); z-index: 1; }
/* No font-weight change on the active step — bolder digits widen the button
   and nudge the whole row on every selection. */
.guide-nav-btn.active { background: var(--brand-2); color: #fff; border-color: var(--brand-2); z-index: 2; }
.guide-nav-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.guide-nav-action {
  width: 32px; height: 32px;
  flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  border-radius: var(--r-sm); color: var(--fg-2);
  border: 1px solid var(--line); background: var(--bg-1);
  transition: all 0.15s;
}
.guide-nav-action:hover:not(:disabled) { background: var(--bg-3); color: var(--fg-0); }
.guide-nav-action:disabled { opacity: 0.3; cursor: not-allowed; }
.guide-nav-action--danger:hover:not(:disabled) { background: color-mix(in oklch, var(--err) 12%, transparent); color: var(--err); border-color: color-mix(in oklch, var(--err) 30%, transparent); }

/* Always rendered with a fixed footprint so showing/hiding never shifts the
   row; fades in only after a short delay so quick refreshes (e.g. the
   subscription-triggered ones after every save) don't flash. */
.guide-nav-loading {
  width: 14px;
  flex: 0 0 14px;
  display: flex;
  align-items: center;
  color: var(--fg-3);
  animation: spin 1s linear infinite;
  opacity: 0;
  transition: opacity 0.15s;
}
.guide-nav-loading.visible {
  opacity: 1;
  transition-delay: 0.3s;
}
@keyframes spin { to { transform: rotate(360deg); } }

.loading-state { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--fg-3); font-size: 13px; }

.spacer { flex: 1; }
</style>
