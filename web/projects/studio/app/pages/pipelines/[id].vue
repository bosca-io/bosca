<script setup lang="ts">
import gql from 'graphql-tag'
import { pipelineEventLabel } from '~/components/pipelines/pipelineEventLabel'
import { useAuth } from '@bosca/auth-client-browser'
import { VueFlow, useVueFlow, type Node as FlowNode, type Edge as FlowEdge, type Connection } from '@vue-flow/core'
import PipelineLinkToGitModal from '~/components/pipelines/PipelineLinkToGitModal.vue'
import PipelineGroupNode from '~/components/pipelines/PipelineGroupNode.vue'
import PipelineNodeCard from '~/components/pipelines/PipelineNodeCard.vue'
import PipelineNodeInspector from '~/components/pipelines/PipelineNodeInspector.vue'
import PipelinePeekModal from '~/components/pipelines/PipelinePeekModal.vue'
import PipelineNodeBrowser from '~/components/pipelines/PipelineNodeBrowser.vue'
import PipelineRunsModal from '~/components/pipelines/PipelineRunsModal.vue'
import PipelineTypeBrowser from '~/components/pipelines/PipelineTypeBrowser.vue'
import { computePipelineLayout } from '~/components/pipelines/pipelineLayout'
import { remapPastedGraph } from '~/components/pipelines/pipelineClipboard'
import { seedNodeDefaults } from '~/components/pipelines/pipelineSettings'
import { collectBrowsableTypes, collectCastTypes, groupNodeTypes, inputNodeDeclaredOutput, nodeTypeSelection, schemaFields, shortTypeName, type NodeType, type NodeInputSlotMeta, type NodeOutputSlotMeta, type NodeFieldMeta, type ShapeField, type MessageProjectInfo } from '~/components/pipelines/pipelineNodeTypes'
// Both imports matter: style.css is layout-only; the handle/edge visuals (without which
// connection handles are unsized and impossible to grab) live in theme-default.css.
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

/**
 * Pipeline editor — a Vue Flow canvas over the SAME graph JSON the backend executor runs.
 * The palette is registry-driven (every `@PipelineNodeType` across loaded modules); each node's
 * settings render in a per-type inspector; edges into a Combine node carry the port name that
 * keys its fan-in.
 */

const { accent } = useCurrentSubsystem()
const { mutation: gqlMutation, query: gqlQuery } = useGraphQL()
const toast = useToast()
const route = useRoute()

const pipelineId = computed(() => String(route.params.id))
const isNew = computed(() => pipelineId.value === 'new')

// ─── Stored graph model (mirrors the backend Pipeline/PipelineEdge/PipelineNode shapes) ──────
interface StoredNode {
  type: string
  id: string
  position?: { x: number, y: number }
  [key: string]: unknown
}
interface StoredEdge {
  id: string
  source: string
  target: string
  sourcePort?: string | null
  targetPort?: string | null
}
// A visual group frame (editor-only; rides in the graph JSON, ignored by the executor). Members move
// with it (Vue Flow parentNode) and hide when it collapses. nodeIds may include nested group ids.
interface StoredGroup {
  id: string
  label: string
  description?: string
  x: number
  y: number
  width: number
  height: number
  collapsed: boolean
  color: string | null
  nodeIds: string[]
}
// NodeType / NodeInputSlotMeta / NodeOutputSlotMeta + the setting metadata are shared with the
// inspector and the generic settings renderer — see ~/components/pipelines/pipelineNodeTypes.
interface CatalogEventField { name: string, type: string }

// ─── Reference data: node palette, events, scripts, jobs ───────────────────────────────────
const nodeTypes = ref<NodeType[]>([])
// Complete serializable/domain-supertype catalog from the backend. Unlike browsableTypes, entries do
// not need a projected field structure, so non-serializable event interfaces remain valid Cast targets.
const cataloguedTypeNames = ref<string[]>([])
const eventOptions = ref<{ value: string, label: string }[]>([])
// Reusable named object shapes (`pipelines.shapes`) — first-class types referenced as `shape:<name>`.
const namedShapes = ref<{ name: string, fields: ShapeField[] }[]>([])
// Hosted message projects with each template's payload contract — the MESSAGE_PROJECT reference, the
// per-node MESSAGE_TEMPLATE picker + payload-contract display, and the `email:` type-catalog entries.
const messageProjects = ref<MessageProjectInfo[]>([])

// ─── Type Browser ──────────────────────────────────────────────────────────────────────────
// The distinct object types that flow between nodes (with their fields), for introspection — plus every
// reusable named shape and every hosted email template's payload contract (`email:<project>/<template>`),
// so `ReleaseBundle` and a welcome email's payload are first-class types in the pickers and the browser.
const browsableTypes = computed(() => [
  ...collectBrowsableTypes(nodeTypes.value),
  ...namedShapes.value.map(s => ({ name: `shape:${s.name}`, shortName: s.name, fields: s.fields })),
  ...messageProjects.value.flatMap(p => p.templates
    .filter(t => t.payloadSchema != null)
    .map(t => ({
      name: `email:${p.project}/${t.key}`,
      shortName: `${p.project}/${t.key} (email)`,
      fields: schemaFields(t.payloadSchema),
    }))),
].sort((a, b) => a.shortName.localeCompare(b.shortName)))
const castTypes = computed(() => collectCastTypes(cataloguedTypeNames.value, browsableTypes.value))
const typeBrowserOpen = ref(false)
const typeBrowserInitial = ref<string | null>(null)
function openTypeBrowser(name: string | null = null) {
  typeBrowserInitial.value = name
  typeBrowserOpen.value = true
}
provide('openTypeBrowser', openTypeBrowser)
provide('pipelineTypeCatalog', browsableTypes)

// The Node Browser — the palette's grid-of-cards sibling (same taxonomy, far more visible at once).
const nodeBrowserOpen = ref(false)

// Save the current shape as a reusable named type (so it appears in every type picker + the Type Browser),
// then refresh the catalog. Provided so the shape builder (nested in the inspector) can call it.
async function saveNamedShape(name: string, fields: ShapeField[]) {
  await gqlMutation(gql`mutation($name: String!, $fields: JSON!) { pipelines { saveShape(name: $name, fields: $fields) { name } } }`,
    { name, fields })
  const res = await gqlQuery<{ pipelines: { shapes: { name: string, fields: ShapeField[] }[] } }>(
    gql`query { pipelines { shapes { name fields { name type } } } }`)
  namedShapes.value = res?.pipelines?.shapes ?? namedShapes.value
}
provide('saveNamedShape', saveNamedShape)

/** Delete a reusable named shape, then refresh the catalog. Accepts a bare name or a `shape:` catalog id. */
async function deleteNamedShape(name: string) {
  const bare = name.startsWith('shape:') ? name.slice('shape:'.length) : name
  await gqlMutation(gql`mutation($name: String!) { pipelines { deleteShape(name: $name) } }`, { name: bare })
  namedShapes.value = namedShapes.value.filter(s => s.name !== bare)
}
provide('deleteNamedShape', deleteNamedShape)
const eventFields = ref<Map<string, CatalogEventField[]>>(new Map())
const scriptOptions = ref<{ value: string, label: string }[]>([])
const jobOptions = ref<{ value: string, label: string }[]>([])
const searchIndexOptions = ref<{ value: string, label: string }[]>([])
// Registered profile attribute types — the typeId picker on a Set Profile Attribute node.
const attributeTypeOptions = ref<{ value: string, label: string }[]>([])
// Named pipeline secrets — the picker for a REFERENCE→SECRET setting (e.g. a Slack/webhook credential).
const secretOptions = ref<{ value: string, label: string }[]>([])
// Audience segments — the SEGMENT reference used by segmentation fan-out nodes.
const segmentOptions = ref<{ value: string, label: string }[]>([])
const gitPipelineOptions = ref<{ value: string, label: string }[]>([])
// The global environment-type catalog — the ENVIRONMENT_TYPE reference (Get Environment's picker).
const environmentTypeOptions = ref<{ value: string, label: string }[]>([])
// The notification-type catalog — the NOTIFICATION_TYPE reference (Send Message Template's type picker).
const notificationTypeOptions = ref<{ value: string, label: string }[]>([])
interface PipelineSummary {
  id: string
  name: string
  description: string
  acceptedInputType: string
  inputSchema: Record<string, unknown> | null
  outputType: string | null
  hasOutput: boolean
  inputFields: { name: string, type: string }[]
  outputFields: { name: string, type: string }[]
}
const allPipelines = ref<PipelineSummary[]>([])
// A pipeline must not invoke itself directly, so the Run Pipeline picker omits this pipeline.
const pipelineOptions = computed(() =>
  allPipelines.value
    .filter(p => p.id !== pipelineId.value)
    .map(p => ({
      value: p.id,
      label: p.name,
      description: p.description,
      acceptedInputType: p.acceptedInputType,
      inputSchema: p.inputSchema,
      outputType: p.outputType,
      hasOutput: p.hasOutput,
      inputFields: resolveShapeFields(p.acceptedInputType, p.inputFields ?? []),
      outputFields: resolveShapeFields(p.outputType, p.outputFields ?? []),
    })))

// A `shape:<name>` reference resolves to the named shape's fields; an inline "SHAPE" keeps the node's fields.
function resolveShapeFields(typeStr: string | null, nodeFields: { name: string, type: string }[]): { name: string, type: string }[] {
  if (typeStr?.startsWith('shape:')) {
    const name = typeStr.slice('shape:'.length)
    return namedShapes.value.find(s => s.name === name)?.fields ?? nodeFields
  }
  return nodeFields
}

// The node-type selection (slots, settings, bounded structure depth) is shared with the node
// browser page via nodeTypeSelection so the two views can't drift.
const NODE_TYPE_FIELDS = nodeTypeSelection(5)

async function loadReferenceData() {
  try {
    const res = await gqlQuery<{
      pipelines: { nodeTypes: NodeType[], types: string[], all: PipelineSummary[], shapes: { name: string, fields: ShapeField[] }[] }
      events: { catalog: { events: { fqdn: string, displayName: string, fields: CatalogEventField[] }[] } }
      scripts: { all: { id: string, key: string }[] }
      scheduler: { availableJobDefinitions: { name: string, displayName: string }[] }
      storageSystems: { all: { id: string, name: string, type: string }[] }
      profiles: { attributeTypes: { all: { id: string, name: string }[] } }
    }>(gql`
      query GetPipelineEditorData {
        pipelines {
          nodeTypes { ${NODE_TYPE_FIELDS} }
          types
          all { id name description acceptedInputType inputSchema outputType hasOutput inputFields { name type } outputFields { name type } }
          shapes { name fields { name type } }
        }
        events { catalog { events { fqdn displayName fields { name type } } } }
        scripts { all { id key } }
        scheduler { availableJobDefinitions { name displayName } }
        storageSystems { all { id name type } }
        profiles { attributeTypes { all { id name } } }
      }
    `)
    nodeTypes.value = res?.pipelines?.nodeTypes ?? []
    cataloguedTypeNames.value = res?.pipelines?.types ?? []
    namedShapes.value = res?.pipelines?.shapes ?? []
    eventOptions.value = (res?.events?.catalog?.events ?? [])
      .map(e => ({ value: e.fqdn, label: pipelineEventLabel(e.displayName || e.fqdn) }))
    eventFields.value = new Map((res?.events?.catalog?.events ?? []).map(e => [e.fqdn, e.fields ?? []]))
    scriptOptions.value = (res?.scripts?.all ?? []).map(s => ({ value: s.id, label: s.key }))
    allPipelines.value = res?.pipelines?.all ?? []
    jobOptions.value = (res?.scheduler?.availableJobDefinitions ?? [])
      .map(j => ({ value: j.name, label: j.displayName || j.name }))
    searchIndexOptions.value = (res?.storageSystems?.all ?? [])
      .filter(s => s.type === 'SEARCH' && s.name.trim().length > 0)
      .map(s => ({ value: s.name, label: s.name }))
    attributeTypeOptions.value = (res?.profiles?.attributeTypes?.all ?? [])
      .map(t => ({ value: t.id, label: t.name?.trim() ? `${t.name} (${t.id})` : t.id }))
  }
  catch {
    toast.error('Failed to load the pipeline editor reference data')
  }
}

/**
 * Named pipeline secrets for the REFERENCE→SECRET picker. Kept OUT of {@link loadReferenceData}'s
 * combined query because the `secrets` field is admin-gated (throws for non-admins): a non-admin who
 * can still edit pipelines gets an empty secret picker rather than a failed editor load.
 */
async function loadSecrets() {
  try {
    const res = await gqlQuery<{ pipelines: { secrets: { name: string }[] } }>(gql`
      query GetPipelineSecretNames { pipelines { secrets { name } } }
    `)
    secretOptions.value = (res?.pipelines?.secrets ?? []).map(s => ({ value: s.name, label: s.name }))
  }
  catch {
    // Non-admin (or secrets unavailable): leave the picker empty; the editor still works.
    secretOptions.value = []
  }
}

// Audience segments — kept out of the combined query so the editor remains usable when the
// segmentation module is unavailable or the caller cannot list segments.
async function loadSegments() {
  try {
    const res = await gqlQuery<{ segments: { all: { id: string, name: string }[] } }>(gql`
      query GetPipelineSegments { segments { all(offset: 0, limit: 100) { id name } } }
    `)
    segmentOptions.value = (res?.segments?.all ?? []).map(segment => ({
      value: segment.id,
      label: segment.name,
    }))
  }
  catch {
    segmentOptions.value = []
  }
}

// All git-ci pipelines — the GIT_PIPELINE reference (e.g. the Use Artifact node's pipeline picker).
async function loadGitPipelines() {
  try {
    const res = await gqlQuery<{ git: { allPipelines: { id: string, name: string }[] } }>(gql`
      query GetAllGitPipelines { git { allPipelines { id name } } }
    `)
    gitPipelineOptions.value = (res?.git?.allPipelines ?? []).map(p => ({ value: p.id, label: p.name }))
  }
  catch {
    // Git-ci unavailable / no permission: leave the picker empty; the editor still works.
    gitPipelineOptions.value = []
  }
}

// The global environment-type catalog — the ENVIRONMENT_TYPE reference (a relay's Get Environment
// names a type; the concrete environment resolves per-run within the release's own program).
async function loadEnvironmentTypes() {
  try {
    const res = await gqlQuery<{ workOps: { multiRepo: { environmentTypes: { name: string }[] } } }>(gql`
      query GetEnvironmentTypes { workOps { multiRepo { environmentTypes { name } } } }
    `)
    environmentTypeOptions.value = (res?.workOps?.multiRepo?.environmentTypes ?? []).map(t => ({ value: t.name, label: t.name }))
  }
  catch {
    environmentTypeOptions.value = []
  }
}

/**
 * Hosted message projects for the MESSAGE_PROJECT/MESSAGE_TEMPLATE pickers, with each template's
 * serializer-derived payload contract. Kept OUT of the combined query: the field is editor-gated
 * (throws for non-editors) and needs a reachable BML Message Server — either way the
 * pickers just sit empty and the editor still works.
 */
async function loadMessageProjects() {
  try {
    const res = await gqlQuery<{ communications: { bmlMessageHostedProjects: MessageProjectInfo[] } }>(gql`
      query GetHostedMessageProjects {
        communications { bmlMessageHostedProjects { project templates { key samplePayload payloadSchema supportsEmail } } }
      }
    `)
    messageProjects.value = (res?.communications?.bmlMessageHostedProjects ?? [])
      .map(project => ({ ...project, templates: project.templates.filter(template => template.supportsEmail) }))
      .filter(project => project.templates.length > 0)
  }
  catch {
    messageProjects.value = []
  }
}

// The notification-type catalog — the NOTIFICATION_TYPE reference (Send Message Template's type picker).
async function loadNotificationTypes() {
  try {
    const res = await gqlQuery<{ communications: { notificationTypes: { key: string, name: string }[] } }>(gql`
      query GetNotificationTypes { communications { notificationTypes { key name } } }
    `)
    notificationTypeOptions.value = (res?.communications?.notificationTypes ?? [])
      .map(t => ({ value: t.key, label: t.name?.trim() ? `${t.name} (${t.key})` : t.key }))
  }
  catch {
    notificationTypeOptions.value = []
  }
}

// Palette search: matches the node's label, key, or description, case-insensitively.
const paletteFilter = ref('')
function matchesPaletteFilter(t: NodeType): boolean {
  const q = paletteFilter.value.trim().toLowerCase()
  if (!q) return true
  return t.label.toLowerCase().includes(q)
    || t.key.toLowerCase().includes(q)
    || t.description.toLowerCase().includes(q)
}

// Organizational taxonomy (group → subgroup, declared on each node's annotation) — the same
// bucketing the node browser page renders, so palette and browser can't drift.
const paletteGroups = computed(() => groupNodeTypes(nodeTypes.value.filter(matchesPaletteFilter)))

// ─── Pipeline metadata + canvas state ────────────────────────────────────────────────────────
const name = ref('')
const description = ref('')
// Free-form categorization labels — grouped and filtered on the pipelines list.
const tags = ref<string[]>([])
// The pipeline's input contract, selectable here and in the Input node inspector (same setting): "JSON"
// (the sentinel for a callable, non-event pipeline — contract is the Input node's schema), a triggering
// event, or a specific catalogued object type (e.g. a ReleaseProjectVersion fed by a Run Pipeline node
// inside a ForEach) — the last drawn from the same type catalog as the Type Browser, deduped vs events.
const inputTypeOptions = computed(() => {
  const eventValues = new Set(eventOptions.value.map(e => e.value))
  const typeOptions = castTypes.value
    .filter(t => !eventValues.has(t.name))
    .map(t => ({ value: t.name, label: t.shortName }))
  return [
    { value: 'JSON', label: 'JSON (callable pipeline)' },
    ...eventOptions.value,
    ...typeOptions,
  ]
})
// REST endpoint exposure (mirrors API scripts): callable at /api/v1/p/{key} when api is on.
const endpointKey = ref('')
const apiEnabled = ref(false)
const isPublic = ref(false)
const triggered = ref(false)
// Cron schedule: when enabled and non-blank, the pipeline runs on this cadence.
const scheduleEnabled = ref(false)
const schedule = ref('')
// Per-pipeline concurrency & rate caps: null = unlimited; excess runs are shed.
const maxConcurrentRuns = ref<number | null>(null)
const maxRunsPerMinute = ref<number | null>(null)
const parseLimit = (v: string): number | null => {
  const n = Number.parseInt(v.trim(), 10)
  return Number.isFinite(n) && n > 0 ? n : null
}
const version = ref(0)
const dirty = ref(false)
const loading = ref(true)
const saving = ref(false)

// ─── Git sync (pipeline serialized as YAML in a PIPELINE_PROJECT repository) ────────────────
const gitRepositoryId = ref<string | null>(null)
const gitPath = ref<string | null>(null)
const lastSyncError = ref<string | null>(null)
const showLinkModal = ref(false)
const gitLinked = computed(() => !!(gitRepositoryId.value && gitPath.value))

const auth = import.meta.client ? useAuth() : null
const profile = auth?.profile ?? null
const commitAuthor = computed(() => resolveGitCommitAuthor(profile?.value))

// The save mutation pushes to Git as a side effect, so the row's sync state can change
// after the response is built — re-read just the git fields instead of reloading the
// whole editor (which would reset the canvas and undo history).
async function refreshGitState() {
  if (isNew.value) return
  try {
    const res = await gqlQuery<{
      pipelines: { pipeline: { gitRepositoryId: string | null, gitPath: string | null, lastSyncError: string | null } | null }
    }>(gql`
      query GetPipelineGitState($id: UUID!) {
        pipelines { pipeline(id: $id) { gitRepositoryId gitPath lastSyncError } }
      }
    `, { id: pipelineId.value })
    const p = res?.pipelines?.pipeline
    if (p) {
      gitRepositoryId.value = p.gitRepositoryId
      gitPath.value = p.gitPath
      lastSyncError.value = p.lastSyncError
    }
  } catch {
    // Non-fatal: the banner just shows the last known state.
  }
}

function onLinked() {
  showLinkModal.value = false
  refreshGitState()
}

// shallowRef: Vue Flow's Node/Edge generics are recursive — deep ref unwrapping both explodes
// TypeScript (TS2589) and is unnecessary, since Vue Flow tracks node state in its own store.
const nodes = shallowRef<FlowNode[]>([])
const edges = shallowRef<FlowEdge[]>([])
const selectedNode = shallowRef<FlowNode | null>(null)
const selectedEdge = shallowRef<FlowEdge | null>(null)

// The pipeline's accepted input type lives in the Input node's settings — that is the single
// source of truth, and this page-level value is a writable view over it. A plain ref carries
// the choice only while no Input node exists (before the graph hydrates, or after the node is
// removed); the next Input node added is seeded from it.
const acceptedInputTypeFallback = ref('')
const acceptedInputType = computed<string>({
  get: () => {
    const input = nodes.value.find(n => n.data.kind === 'input')
    if (!input) return acceptedInputTypeFallback.value
    const v = (input.data.settings as Record<string, unknown>).acceptedType
    return typeof v === 'string' ? v : ''
  },
  set: (v) => {
    acceptedInputTypeFallback.value = v
    const input = nodes.value.find(n => n.data.kind === 'input')
    if (input) {
      const settings = input.data.settings as Record<string, unknown>
      settings.acceptedType = v
      // nodes is a shallowRef and settings are mutated in place — nudge dependents.
      triggerRef(nodes)
    }
  },
})
const isJsonInput = computed(() => acceptedInputType.value === 'JSON')

// A JSON-input pipeline cannot be event-triggered. When the input becomes the JSON sentinel,
// drop the flag so the persisted row is honest — switching back to an event later must not
// silently reactivate the pipeline; the author re-enables Active explicitly.
watch(isJsonInput, (v) => {
  if (v) triggered.value = false
})

const { onConnect, onConnectStart, onConnectEnd, onNodeClick, onEdgeClick, onPaneClick, onNodeDragStop, addEdges, findNode, screenToFlowCoordinate, getIntersectingNodes, updateNode, fitView } = useVueFlow()

let seq = 0
function uid(prefix: string): string {
  seq += 1
  return `${prefix}${Date.now().toString(36)}${seq}`
}
function nextNodeId(): string {
  return uid('n')
}

function typeOf(key: string): NodeType | undefined {
  return nodeTypes.value.find(t => t.key === key)
}

// ─── Peek into a referenced pipeline (the card's corner eye / the inspector's button) ─────────────
/** The pipeline a node references, read from its type's first PIPELINE-reference setting with a value. */
function nodePeekTarget(data: { kind?: unknown, settings?: Record<string, unknown> }): string | null {
  for (const slot of typeOf(String(data.kind ?? ''))?.settings ?? []) {
    if (slot.reference !== 'PIPELINE') continue
    const value = data.settings?.[slot.name]
    if (typeof value === 'string' && value.length > 0) return value
  }
  return null
}
const peekTargetId = ref<string | null>(null)
const peekTargetName = computed(() =>
  peekTargetId.value ? allPipelines.value.find(p => p.id === peekTargetId.value)?.name : undefined)

/** The declared input requirements of the selected node's type — shown in the inspector's "Expects" section. */
const selectedNodeInputs = computed<NodeInputSlotMeta[]>(() => {
  const kind = selectedNode.value?.data?.kind
  return kind ? typeOf(String(kind))?.inputs ?? [] : []
})

/** The selected node type's declared settings — the form the inspector renders generically. */
const selectedNodeSettings = computed(() => {
  const kind = selectedNode.value?.data?.kind
  return kind ? typeOf(String(kind))?.settings ?? [] : []
})

/** What the selected node's type produces — shown in the inspector's "Produces" section. */
const selectedNodeProduces = computed(() => {
  const kind = selectedNode.value?.data?.kind
  // The Input node produces its declared accepted type — surface it (with structure) so the author sees
  // and can introspect exactly what flows into the pipeline, instead of a generic value.
  if (kind === 'input') {
    const accepted = acceptedInputType.value
    // An inline object shape carries its typed fields on the node itself.
    if (accepted === 'SHAPE') {
      const fields = (selectedNode.value?.data?.settings?.fields as ShapeField[] | undefined) ?? []
      return { typeLabel: 'Object', description: null, type: null, kind: 'OBJECT', structure: fields, outputs: [] as NodeOutputSlotMeta[] }
    }
    const typed = accepted && accepted !== 'JSON' ? accepted : null
    const cat = typed ? browsableTypes.value.find(b => b.name === typed) : undefined
    return {
      typeLabel: typed ? (cat?.shortName ?? shortTypeName(typed)) : null,
      description: null,
      type: typed,
      kind: typed ? 'OBJECT' : 'ANY',
      structure: cat?.fields ?? null,
      outputs: [] as NodeOutputSlotMeta[],
    }
  }
  // For Each / Run Pipeline produce a value derived from the body pipeline they reference (For Each as a
  // list) — surface that instead of the generic ActionNode "no declared output".
  if (kind === 'forEach' || kind === 'runPipeline') {
    const bodyId = (selectedNode.value?.data?.settings as Record<string, unknown> | undefined)?.pipelineId
    const body = typeof bodyId === 'string' ? pipelineOptions.value.find(p => p.value === bodyId) : undefined
    const derived = declaredSourceOutput(findNode(selectedNode.value?.id ?? ''))
    // A shape body carries its typed fields through — For Each as a list of the shape. A named shape keeps
    // its type name (e.g. ReleaseBundle[]); an inline/anonymous shape shows as Object[].
    const structure = body?.outputFields?.length ? body.outputFields.map(f => ({ name: f.name, type: f.type })) : null
    return {
      typeLabel: derived.type ? shortTypeName(derived.type) : (structure ? (kind === 'forEach' ? 'Object[]' : 'Object') : null),
      description: null,
      type: derived.type,
      kind: derived.kind ?? 'ANY',
      structure,
      outputs: [] as NodeOutputSlotMeta[],
    }
  }
  // Flatten's output is its inbound array with one level removed — resolve it through the wire, and its
  // element type's fields from the catalog (so a named shape shows its fields, not just the name).
  if (kind === 'flatten') {
    const derived = effectiveSourceOutput(findNode(selectedNode.value?.id ?? ''), null)
    const base = derived.type ? derived.type.replace(/(\[\])+$/, '') : null
    const structure = base ? browsableTypes.value.find(t => t.name === base)?.fields ?? null : null
    return {
      typeLabel: derived.type ? shortTypeName(derived.type) : null,
      description: null,
      type: derived.type,
      kind: derived.kind ?? 'ARRAY',
      structure,
      outputs: [] as NodeOutputSlotMeta[],
    }
  }
  // Objects → Map builds an object keyed by its incoming ports — surface that detected shape.
  if (kind === 'objectsToMap') {
    return {
      typeLabel: 'Object',
      description: null,
      type: null,
      kind: 'OBJECT',
      structure: objectsToMapStructure(findNode(selectedNode.value?.id ?? '')),
      outputs: [] as NodeOutputSlotMeta[],
    }
  }
  // The Output passes its inbound value through — reflect what's wired in, incl. a detected object shape,
  // so the author (and consumers) see the actual output instead of an opaque "JSON".
  if (kind === 'output') {
    const inbound = edges.value.find(e => e.target === selectedNode.value?.id)
    const src = inbound ? findNode(inbound.source) : undefined
    const eff = src ? effectiveSourceOutput(src, (inbound?.sourceHandle as string | undefined) ?? null) : { kind: 'ANY', type: null }
    const structure = inboundObjectStructure(selectedNode.value?.id)
    return {
      typeLabel: structure ? 'Object' : (eff.type ? shortTypeName(eff.type) : null),
      description: null,
      type: eff.type,
      kind: structure ? 'OBJECT' : eff.kind,
      structure,
      outputs: [] as NodeOutputSlotMeta[],
    }
  }
  // A node that declares its own output type (e.g. JSONata's outputType) — surface that custom type and
  // resolve its fields from the catalog, instead of the node type's dynamic (ANY) output.
  const declaredOut = declaredSourceOutput(findNode(selectedNode.value?.id ?? ''))
  if (declaredOut.type) {
    const base = declaredOut.type.replace(/(\[\])+$/, '')
    return {
      typeLabel: displayTypeOf({ kind: declaredOut.kind ?? 'OBJECT', type: declaredOut.type }),
      description: null,
      type: declaredOut.type,
      kind: declaredOut.kind ?? 'OBJECT',
      structure: browsableTypes.value.find(x => x.name === base)?.fields ?? null,
      outputs: [] as NodeOutputSlotMeta[],
    }
  }
  const t = kind ? typeOf(String(kind)) : undefined
  const outs = t?.outputs ?? []
  // Exactly one non-error output is the node's implicit output — summarize it; otherwise (2+, or an
  // error port alongside) show the named ports.
  const lone = outs.length === 1 && !outs[0]!.error ? outs[0] : undefined
  return {
    typeLabel: lone?.typeLabel ?? null,
    description: lone?.description ?? null,
    type: lone?.type ?? null,
    kind: lone?.kind ?? 'ANY',
    structure: lone?.structure ?? null,
    outputs: lone ? [] : outs,
  }
})

// ─── Slot type constraints (mirrors the backend SlotConnectionValidator) ──────────────────────
/** Whether a value of `source` kind may flow into a `target`-kind slot, with the same widenings. */
function isKindAssignable(source: string, target: string): boolean {
  if (target === 'ANY' || source === target) return true
  if (target === 'NUMBER' && source === 'INTEGER') return true // an integer is a number
  // A UUID does NOT widen into a string — it's an identifier, not arbitrary text. Convert it
  // explicitly with a To String node. (An unknown/ANY source is likewise never assignable to a typed slot.)
  return false
}

/**
 * A source node's instance-declared output, mirroring the backend SlotConnectionValidator's
 * DeclaredOutput override (bosca.pipelines.builtin.JsonataNode): a dynamic node (JSONata) can pin its
 * result's `outputKind` — and, for an object, a specific `outputType` — so a typed slot accepts the
 * wire. A declared object type implies OBJECT even when the kind is left ANY. Returns `kind: null`
 * when nothing is declared, so the caller falls back to the node TYPE's static output.
 */
function declaredSourceOutput(node: ReturnType<typeof findNode>): { kind: string | null, type: string | null } {
  const s = (node?.data as { settings?: Record<string, unknown> } | undefined)?.settings
  const nodeKind = String((node?.data as { kind?: unknown } | undefined)?.kind ?? '')

  if (nodeKind === 'input') return inputNodeDeclaredOutput(s?.acceptedType)

  // Run Pipeline / For Each have no outputType of their own — they derive it from the body pipeline they
  // reference. Run Pipeline passes the body's declared output straight through; For Each aggregates each
  // item's result into a list, so it appends one array level (`X` → `X[]`, `X[]` → `X[][]`).
  if (nodeKind === 'runPipeline' || nodeKind === 'forEach') {
    const bodyId = typeof s?.pipelineId === 'string' ? s.pipelineId : null
    const body = bodyId ? pipelineOptions.value.find(p => p.value === bodyId) : null
    // Inline (anonymous) shape body — an object with no type name; For Each makes it a list. A NAMED shape
    // (`shape:X`) has a real type name, so it falls through to the general derivation below → `shape:X[]`.
    if (body?.outputType === 'SHAPE') {
      return { kind: nodeKind === 'forEach' ? 'ARRAY' : 'OBJECT', type: null }
    }
    const bodyOut = body?.outputType && body.outputType !== 'JSON' ? body.outputType : null
    if (!bodyOut) return { kind: null, type: null }
    if (nodeKind === 'forEach') return { kind: 'ARRAY', type: `${bodyOut}[]` }
    return { kind: bodyOut.endsWith('[]') ? 'ARRAY' : 'OBJECT', type: bodyOut }
  }

  const rawKind = typeof s?.outputKind === 'string' ? s.outputKind : null
  const type = typeof s?.outputType === 'string' && s.outputType.trim() !== '' ? s.outputType.trim() : null
  const kind = (!rawKind || rawKind === 'ANY') ? (type ? 'OBJECT' : null) : rawKind
  return { kind, type }
}

/**
 * A source node's effective output kind/type for the wire leaving `handle`, threading through routing
 * nodes: a ROUTE node (Condition/Switch) passes its input straight through, so its output is whatever
 * feeds its single input — resolved recursively. Otherwise it's the instance declaration, then the
 * named port, then the node-level output. Mirrors the backend SlotConnectionValidator.
 */
function effectiveSourceOutput(node: ReturnType<typeof findNode>, handle: string | null, visited: Set<string> = new Set()): { kind: string, type: string | null } {
  if (!node || visited.has(node.id)) return { kind: 'ANY', type: null }
  const nodeKind = String((node.data as { kind?: unknown }).kind)
  const t = typeOf(nodeKind)
  if (t?.category === 'ROUTE') {
    const inbound = edges.value.find(e => e.target === node.id)
    if (!inbound) return { kind: 'ANY', type: null }
    return effectiveSourceOutput(findNode(inbound.source), (inbound.sourceHandle as string | undefined) ?? null, new Set([...visited, node.id]))
  }
  // Flatten removes one array level from whatever feeds it — resolve its inbound source, then strip a
  // level off the type (`X[][]` → `X[]`); a single-level array flattens to the same flat array.
  if (nodeKind === 'flatten') {
    const inbound = edges.value.find(e => e.target === node.id)
    if (!inbound) return { kind: 'ARRAY', type: null }
    const src = effectiveSourceOutput(findNode(inbound.source), (inbound.sourceHandle as string | undefined) ?? null, new Set([...visited, node.id]))
    const type = src.type?.endsWith('[][]') ? src.type.slice(0, -2) : src.type
    return { kind: 'ARRAY', type }
  }
  const declared = declaredSourceOutput(node)
  // Match the wire's port by name, or — for an edge off a single-output node's anonymous handle (no
  // handle name) — the lone non-error output. No outputs at all = an implicit ANY output.
  const outs = t?.outputs ?? []
  const nonError = outs.filter(o => !o.error)
  const port = outs.find(o => o.name === handle) ?? (nonError.length === 1 ? nonError[0] : undefined)
  return {
    kind: declared.kind ?? port?.kind ?? 'ANY',
    type: declared.type ?? port?.type ?? null,
  }
}

/** A readable type label for a resolved source output — its short type name, with `[]` for an array. */
function displayTypeOf(src: { kind: string, type: string | null }): string {
  if (!src.type) return src.kind === 'ARRAY' ? 'array' : src.kind === 'OBJECT' ? 'object' : src.kind.toLowerCase()
  const short = shortTypeName(src.type)
  return (src.kind === 'ARRAY' && !short.endsWith('[]')) ? `${short}[]` : short
}

/**
 * The object shape an Objects → Map node builds: one field per incoming edge, keyed by the edge's target
 * port (the map key), typed by resolving that edge's source. Lets the editor carry a real `{ repositories:
 * ProjectRepository[], version: Version }` shape across to the Output instead of an opaque "JSON".
 */
function objectsToMapStructure(node: ReturnType<typeof findNode>): NodeFieldMeta[] {
  if (!node) return []
  return edges.value
    .filter(e => e.target === node.id)
    .map((e) => {
      const src = effectiveSourceOutput(findNode(e.source), (e.sourceHandle as string | undefined) ?? null)
      return { name: edgeTargetPort(e) ?? '?', type: displayTypeOf(src) }
    })
}

/** The detected structure of whatever a node's inbound edge carries, when that source builds an object shape. */
function inboundObjectStructure(nodeId: string | undefined): NodeFieldMeta[] | null {
  const inbound = edges.value.find(e => e.target === nodeId)
  const src = inbound ? findNode(inbound.source) : undefined
  if (src && String((src.data as { kind?: unknown }).kind) === 'objectsToMap') return objectsToMapStructure(src)
  return null
}

/** The full type value (a serial name, `[]`-suffixed for arrays) for a resolved source — matches the shape builder's options. */
function fullTypeOf(src: { kind: string, type: string | null }): string {
  if (!src.type) return 'JSON'
  return (src.kind === 'ARRAY' && !src.type.endsWith('[]')) ? `${src.type}[]` : src.type
}

/** The selected Output node's detected shape (from a wired Objects → Map), as storable ShapeFields — for one-click adoption. */
const selectedNodeDetectedShape = computed<ShapeField[] | null>(() => {
  const node = selectedNode.value
  if (!node || node.data?.kind !== 'output') return null
  const inbound = edges.value.find(e => e.target === node.id)
  const src = inbound ? findNode(inbound.source) : undefined
  if (!src || String((src.data as { kind?: unknown }).kind) !== 'objectsToMap') return null
  return edges.value
    .filter(e => e.target === src.id)
    .map((e) => {
      const s = effectiveSourceOutput(findNode(e.source), (e.sourceHandle as string | undefined) ?? null)
      return { name: edgeTargetPort(e) ?? '?', type: fullTypeOf(s) }
    })
})

/** Human label for a slot's constraint, e.g. "uuid" or "object · bosca.profile.model.Profile". */
function slotConstraintLabel(s: NodeInputSlotMeta): string {
  return s.type ? `${s.kind.toLowerCase()} · ${s.type}` : s.kind.toLowerCase()
}

/** The slot an edge targets: a single-slot node ignores the handle; a multi-slot node matches by name. */
function matchSlot(target: NodeType, targetHandle: string | null | undefined): NodeInputSlotMeta | undefined {
  if (target.inputs.length === 1) return target.inputs[0]
  return target.inputs.find(s => s.name === targetHandle)
}

/**
 * The target port an edge feeds. For a node with declared input slots the port is the input handle
 * the edge lands on (`targetHandle`); for a free-form node (no declared slots, e.g. Objects → Map)
 * it is the operator-named edge label.
 */
function edgeTargetPort(e: FlowEdge): string | null {
  if (typeof e.targetHandle === 'string' && e.targetHandle.length > 0) return e.targetHandle
  return e.label != null ? String(e.label) : null
}
/**
 * Whether a node's type renders per-slot input handles (more than one declared slot). Single-input
 * and free-form nodes keep the single anonymous handle, so their edges stay label/handle-less — this
 * mirrors PipelineNodeCard's `hasMultipleInputs`.
 */
function nodeHasInputPorts(type: string | undefined): boolean {
  return (typeOf(String(type))?.inputs?.length ?? 0) > 1
}
/** The kind of an already-hydrated node by id — `nodes` is set before edges are mapped, on load and undo/redo. */
function targetTypeOf(nodeId: string): string | undefined {
  const n = nodes.value.find(x => x.id === nodeId)
  return n ? String(n.data.kind) : undefined
}

/**
 * Connection guard with a reason: returns a human message when the edge would be a mistake, or null
 * when it's allowed. Beyond the slot-kind check (which mirrors the backend SlotConnectionValidator),
 * it refuses three structural errors Vue Flow would otherwise drop silently: a node feeding itself,
 * a duplicate edge, and a second source fanning into a single-input slot. The server re-validates
 * regardless; this is faster feedback (and, via the rejection message, an explanation).
 */
function connectionRejection(connection: Connection): string | null {
  // A node can't feed itself.
  if (connection.source === connection.target) return 'A node can\'t connect to itself'

  const sourceNode = findNode(connection.source)
  const targetNode = findNode(connection.target)
  if (!sourceNode || !targetNode) return null

  const targetHandle = connection.targetHandle ?? null
  const sourceHandle = connection.sourceHandle ?? null

  // Vue Flow runs this guard over EXISTING edges too (during graph load / createGraphEdges), passing
  // the edge itself as `connection` — which has an `id`. A fresh drag has none. Exclude the edge being
  // validated from the "already exists" scans below, or every loaded edge flags itself and is dropped.
  const selfId = (connection as { id?: string }).id

  // The exact same wire already exists (same source port → same target port).
  const isDuplicate = edges.value.some(e =>
    e.id !== selfId
    && e.source === connection.source
    && e.target === connection.target
    && (e.sourceHandle ?? null) === sourceHandle
    && edgeTargetPort(e) === targetHandle)
  if (isDuplicate) return 'These ports are already connected'

  const targetType = typeOf(String(targetNode.data.kind))
  if (!targetType || targetType.inputs.length === 0) return null
  const slot = matchSlot(targetType, targetHandle)
  if (!slot) return null

  // Single-input slots take one source; only block a SECOND edge into the same slot. A multi-input
  // node may have one edge per named slot — fan-in is keyed by the stored targetPort (the edge label).
  const slotName = targetType.inputs.length > 1 ? slot.name : null
  const slotOccupied = edges.value.some(e =>
    e.id !== selfId
    && e.target === connection.target
    && (slotName === null || edgeTargetPort(e) === slotName))
  if (slotOccupied) return `Input '${slot.name}' already has a connection`

  // An instance-level slot requirement (mirrors the backend HasDeclaredInputs): an email template
  // node with a template picked requires that template's payload contract on its payload port, so
  // the slot behaves as a type-pinned OBJECT slot even though it's ANY by type.
  const declaredInput = declaredTargetInputType(targetNode, slot.name)
  const slotKind = declaredInput ? 'OBJECT' : slot.kind
  const slotType = declaredInput ?? slot.type

  // Slot-kind compatibility (mirrors the backend SlotConnectionValidator). An ANY slot accepts
  // anything. But a typed slot refuses an unknown source — a dynamic node (outputKind ANY, e.g.
  // JSONata) or a node with no declared output (the Input node): "if you can't prove it works, it
  // doesn't." Route such a value through a node that produces the right kind (e.g. Get Id for a UUID).
  if (slotKind === 'ANY') return null
  // The source's effective output — threaded through routing nodes, which pass their input straight
  // through (see effectiveSourceOutput), with the per-instance/per-port/node-level fallbacks.
  const effective = effectiveSourceOutput(sourceNode, sourceHandle)
  const sourceKind = effective.kind
  // A routing node (Condition/Switch) is a transparent passthrough. If the flowing type can't be
  // pinned (e.g. straight off the untyped JSON Input), trust it into an OBJECT/ARRAY slot — payloads
  // are the natural thing to route. A SCALAR slot (string/uuid/number/…) is NOT trusted: those come
  // from specific producers, so an unpinned value into one is rejected here, not left to fail at run
  // time. A known-but-wrong type is still caught below; a non-routing unknown source is still refused.
  const sourceIsRouting = typeOf(String((sourceNode.data as { kind?: unknown }).kind))?.category === 'ROUTE'
  if (sourceIsRouting && sourceKind === 'ANY' && (slotKind === 'OBJECT' || slotKind === 'ARRAY')) return null
  if (!isKindAssignable(sourceKind, slotKind)) {
    if (sourceKind === 'ANY') {
      const hint = slotKind === 'UUID'
        ? ' Add a Get Id node to extract one.'
        : declaredInput ? ` Add a Cast node to '${shortTypeName(declaredInput)}' first.` : ''
      return `This source produces an unknown value, but '${slot.name}' needs a ${declaredInput ? shortTypeName(declaredInput) : slotKind.toLowerCase()}.${hint}`
    }
    return `Output kind ${sourceKind} can't feed a ${slotKind} input`
  }
  // Kind matches; a slot that names a specific object type needs the source to produce exactly it
  // (but an untyped value routed through a routing node is trusted, as above).
  if (slotType) {
    const producedType = effective.type
    // An ARRAY slot's `type` is its ELEMENT type (e.g. `kind=ARRAY, type=ArtifactDefinition` means
    // "ArtifactDefinition[]"), so a source producing `<element>[]` satisfies it — compare element-to-
    // element by peeling one array level off the produced type. The error still reports the full type.
    const producedElement = slotKind === 'ARRAY' && producedType?.endsWith('[]')
      ? producedType.slice(0, -2)
      : producedType
    if (producedElement !== slotType && !(sourceIsRouting && producedElement === null)) {
      const want = declaredInput ? shortTypeName(declaredInput) : (slot.typeLabel || slotType.split('.').pop())
      return producedType
        ? `'${slot.name}' needs a ${want}, but this source produces ${shortTypeName(producedType)}.`
        : `'${slot.name}' needs a ${want}, but this source produces an untyped value.`
    }
  }
  return null
}

/**
 * The specific type a target node instance requires on [slotName] — the editor mirror of the backend
 * `HasDeclaredInputs` (SendEmailTemplateNode/RenderEmailTemplateNode): with a template picked, the
 * payload port requires that template's payload contract (`email:<project>/<template>`). The legacy
 * single-setting `project/template-key` form is honored the same way the backend's resolveTemplate is.
 */
function declaredTargetInputType(node: ReturnType<typeof findNode>, slotName: string): string | null {
  const kind = String((node?.data as { kind?: unknown } | undefined)?.kind ?? '')
  if ((kind !== 'sendEmailTemplate' && kind !== 'renderEmailTemplate') || slotName !== 'payload') return null
  const s = (node?.data as { settings?: Record<string, unknown> } | undefined)?.settings
  const template = typeof s?.template === 'string' ? s.template : ''
  const project = typeof s?.project === 'string' ? s.project : ''
  const reference = template.includes('/') ? template : (project && template ? `${project}/${template}` : '')
  const slash = reference.indexOf('/')
  if (slash <= 0 || slash === reference.length - 1) return null
  return `email:${reference}`
}

/**
 * Live connection guard for Vue Flow's drag feedback (the handle turns valid/invalid). Defers to
 * connectionRejection — any reason means the edge is refused. A rejected drop is surfaced to the
 * user in onConnectEnd, since Vue Flow itself drops the edge silently.
 */
function isValidConnection(connection: Connection): boolean {
  return connectionRejection(connection) === null
}

// Stored node ⇄ flow node: everything except type/id/position is the node's typed settings.
function toFlowNode(stored: StoredNode): FlowNode {
  const { type, id, position, ...settings } = stored
  const meta = typeOf(type)
  // Input/Output are structural nodes (no @PipelineNodeType descriptor), so derive their category +
  // label by kind — otherwise they fall back to TRANSFORM and render handles they shouldn't have
  // (Input: no inbound handle; Output: no outbound handle).
  const category = type === 'input' ? 'INPUT' : type === 'output' ? 'OUTPUT' : (meta?.category ?? 'TRANSFORM')
  const label = type === 'input' ? 'Input' : type === 'output' ? 'Output' : (meta?.label ?? type)
  return {
    id,
    type: 'pipeline',
    // Default each coordinate: the backend omits a 0.0 x/y (encodeDefaults=false), so a stored
    // position can be absent OR partial (e.g. {x:240} with y dropped) — undefined would render at NaN.
    position: { x: position?.x ?? 0, y: position?.y ?? 0 },
    data: {
      kind: type,
      label,
      category,
      // Declared output ports drive the node's source handles (incl. error ports); transient
      // view-state only — toStoredNode persists just kind + settings, never this.
      outputs: meta?.outputs ?? [],
      // Declared input slots — drives the input-handle tooltip + the inspector's "Expects" section.
      inputs: meta?.inputs ?? [],
      settings: settings as Record<string, unknown>,
    },
  }
}
function toStoredNode(flow: FlowNode): StoredNode {
  // Positions persist as ABSOLUTE canvas coords; a node inside a group frame has a parent-relative
  // Vue Flow position, so read its absolute computedPosition when available.
  const c = findNode(flow.id)?.computedPosition
  const pos = c ?? flow.position
  return {
    // Settings spread FIRST: `type` (the polymorphic discriminator), `id`, and `position` are the
    // stored node's own keys and must win over any same-named setting — a colliding setting once
    // clobbered the discriminator and made the whole pipeline unsaveable.
    ...(flow.data.settings as Record<string, unknown>),
    type: String(flow.data.kind),
    id: flow.id,
    position: { x: pos.x, y: pos.y },
  }
}
function toFlowEdge(stored: StoredEdge): FlowEdge {
  return {
    id: stored.id,
    source: stored.source,
    target: stored.target,
    // sourceHandle carries the routing branch (a Condition node's true/false output port).
    sourceHandle: stored.sourcePort ?? undefined,
    // A declared-input target has a handle per slot, so attach the edge to the named port handle;
    // a free-form target carries the port as the edge label (named on the edge, shown as text).
    targetHandle: nodeHasInputPorts(targetTypeOf(stored.target)) ? (stored.targetPort ?? undefined) : undefined,
    label: nodeHasInputPorts(targetTypeOf(stored.target)) ? undefined : (stored.targetPort ?? undefined),
  }
}
function toStoredEdge(flow: FlowEdge): StoredEdge {
  const sourcePort = typeof flow.sourceHandle === 'string' && flow.sourceHandle.length > 0 ? flow.sourceHandle : null
  return { id: flow.id, source: flow.source, target: flow.target, sourcePort, targetPort: edgeTargetPort(flow) }
}

// ─── Undo / redo: snapshots of the stored graph form, coalesced over typing bursts ──────────
const history = ref<string[]>([])
const historyIndex = ref(-1)
let historyTimer: ReturnType<typeof setTimeout> | null = null
const canUndo = computed(() => historyIndex.value > 0)
const canRedo = computed(() => historyIndex.value >= 0 && historyIndex.value < history.value.length - 1)

// ─── Node groups: visual frames (editor-only; ride in the graph JSON, ignored by the executor) ───
const GROUP_TYPE = 'group'
const GROUP_HEADER_H = 30
let groupSeq = 0
function nextGroupId(): string {
  return `group-${Date.now()}-${groupSeq++}`
}

function isGroup(n: FlowNode): boolean {
  return n.type === GROUP_TYPE
}

/** Absolute canvas position of a flow node (Vue Flow tracks it; falls back to its raw position). */
function absPos(id: string, fallback: { x: number, y: number }): { x: number, y: number } {
  const c = findNode(id)?.computedPosition
  return c ? { x: c.x, y: c.y } : fallback
}

/** A Vue Flow group node from its stored form (absolute position; relativised later if nested). */
function toFlowGroupNode(g: StoredGroup): FlowNode {
  const h = g.collapsed ? GROUP_HEADER_H : g.height
  return {
    id: g.id,
    type: GROUP_TYPE,
    position: { x: g.x, y: g.y },
    style: { width: `${g.width}px`, height: `${h}px`, zIndex: 0 },
    data: { isGroup: true, label: g.label, description: g.description ?? '', collapsed: g.collapsed, color: g.color, expandedHeight: g.height, memberCount: g.nodeIds.length },
  }
}

function toStoredGroup(g: FlowNode): StoredGroup {
  const abs = absPos(g.id, g.position)
  const style = (g.style ?? {}) as Record<string, unknown>
  const d = g.data as { label?: string, description?: string, collapsed?: boolean, color?: string | null, expandedHeight?: number }
  const width = parseFloat(String(style.width ?? '240')) || 240
  const height = d.collapsed ? (d.expandedHeight ?? 160) : (parseFloat(String(style.height ?? '160')) || 160)
  return {
    id: g.id,
    label: d.label ?? '',
    description: d.description ?? '',
    x: abs.x,
    y: abs.y,
    width,
    height,
    collapsed: !!d.collapsed,
    color: d.color ?? null,
    nodeIds: nodes.value.filter(n => n.parentNode === g.id).map(n => n.id),
  }
}

/**
 * The full flow-node list from stored nodes + groups, wiring parent/child (incl. nesting) so members
 * move with their frame. Positions are stored absolute; Vue Flow children are relative to their
 * immediate parent, so we relativise on load. Parents must precede children, so we sort by depth.
 */
function buildFlowNodes(storedNodes: StoredNode[], storedGroups: StoredGroup[]): FlowNode[] {
  const parentOf = new Map<string, string>()
  const absById = new Map<string, { x: number, y: number }>()
  for (const g of storedGroups) {
    absById.set(g.id, { x: g.x, y: g.y })
    for (const nid of g.nodeIds) parentOf.set(nid, g.id)
  }
  for (const n of storedNodes) absById.set(n.id, { x: n.position?.x ?? 0, y: n.position?.y ?? 0 })

  const all = [...storedGroups.map(toFlowGroupNode), ...storedNodes.map(toFlowNode)]
  for (const n of all) {
    const pid = parentOf.get(n.id)
    if (!pid || !absById.has(pid)) continue
    const me = absById.get(n.id)!
    const par = absById.get(pid)!
    n.parentNode = pid
    n.extent = 'parent'
    n.position = { x: me.x - par.x, y: me.y - par.y }
  }
  const depth = (id: string): number => {
    let d = 0
    let c = parentOf.get(id)
    while (c) { d++; c = parentOf.get(c) }
    return d
  }
  return all.sort((a, b) => depth(a.id) - depth(b.id))
}

/** Split the canvas into stored nodes + groups for persistence (positions stored absolute). */
function serializeGraph(): { nodes: StoredNode[], edges: StoredEdge[], groups: StoredGroup[] } {
  return {
    nodes: nodes.value.filter(n => !isGroup(n)).map(toStoredNode),
    edges: edges.value.map(toStoredEdge),
    groups: nodes.value.filter(isGroup).map(toStoredGroup),
  }
}

/** Whether a node is hidden because one of its ancestor frames is collapsed (handles nesting). */
function isHiddenByCollapse(id: string): boolean {
  let c = nodes.value.find(n => n.id === id)?.parentNode
  while (c) {
    const g = nodes.value.find(n => n.id === c)
    if (g && (g.data as { collapsed?: boolean }).collapsed) return true
    c = g?.parentNode
  }
  return false
}

/** Recompute node/edge visibility + frame heights from the collapsed state of every frame. */
function applyGroupVisibility() {
  const hidden = new Set(nodes.value.filter(n => isHiddenByCollapse(n.id)).map(n => n.id))
  nodes.value = nodes.value.map((n) => {
    if (isGroup(n)) {
      const d = n.data as { collapsed?: boolean, expandedHeight?: number }
      const style = { ...((n.style ?? {}) as Record<string, unknown>), height: d.collapsed ? `${GROUP_HEADER_H}px` : `${d.expandedHeight ?? 160}px` }
      return { ...n, hidden: hidden.has(n.id), style }
    }
    return { ...n, hidden: hidden.has(n.id) }
  })
  edges.value = edges.value.map(e => ({ ...e, hidden: hidden.has(e.source) || hidden.has(e.target) }))
}

function refreshMemberCounts() {
  nodes.value = nodes.value.map(n => isGroup(n)
    ? { ...n, data: { ...n.data, memberCount: nodes.value.filter(c => c.parentNode === n.id).length } }
    : n)
}

/** Add an empty group frame; the author moves/resizes it and drops nodes inside to group them. */
function addGroup() {
  const id = nextGroupId()
  const g = toFlowGroupNode({ id, label: 'Group', x: 80, y: 80, width: 300, height: 200, collapsed: false, color: null, nodeIds: [] })
  nodes.value = [g, ...nodes.value] // a frame must precede its (future) children in the array
  dirty.value = true
  pushHistory()
}

function setGroupLabel(id: string, label: string) {
  nodes.value = nodes.value.map(n => n.id === id ? { ...n, data: { ...n.data, label } } : n)
  dirty.value = true
  pushHistory()
}

function setGroupDescription(id: string, description: string) {
  nodes.value = nodes.value.map(n => n.id === id ? { ...n, data: { ...n.data, description } } : n)
  dirty.value = true
  pushHistory()
}

// A selected group, re-read from the live node list so its label/description stay current after an
// edit — the selection holds a snapshot, and setGroupLabel/Description replace the node object.
const selectedGroup = computed<FlowNode | null>(() => {
  const sel = selectedNode.value
  if (!sel || !isGroup(sel)) return null
  return nodes.value.find(n => n.id === sel.id) ?? sel
})
function renameSelectedGroup(v: string) {
  const g = selectedGroup.value
  if (g) setGroupLabel(g.id, v)
}
function describeSelectedGroup(v: string) {
  const g = selectedGroup.value
  if (g) setGroupDescription(g.id, v)
}
function removeSelectedGroup() {
  const g = selectedGroup.value
  if (!g) return
  removeGroup(g.id)
  selectedNode.value = null
}

function toggleGroupCollapsed(id: string) {
  nodes.value = nodes.value.map(n => n.id === id ? { ...n, data: { ...n.data, collapsed: !(n.data as { collapsed?: boolean }).collapsed } } : n)
  applyGroupVisibility()
  dirty.value = true
  pushHistory()
}

/** Remove a frame but keep its nodes — un-parent them and restore their absolute positions. */
function removeGroup(id: string) {
  const par = absPos(id, { x: 0, y: 0 })
  nodes.value = nodes.value
    .filter(n => n.id !== id)
    .map(n => n.parentNode === id
      ? { ...n, parentNode: undefined, extent: undefined, position: { x: n.position.x + par.x, y: n.position.y + par.y } }
      : n)
  applyGroupVisibility()
  dirty.value = true
  pushHistory()
}

/**
 * After a drag, (re)assign the node's group membership by spatial containment: the innermost frame it
 * now overlaps becomes its parent (skipping itself and its own descendants to avoid cycles); dragged
 * fully out of its frame, it un-parents. Positions convert between absolute and parent-relative.
 */
function reparentOnDragStop(dragged: FlowNode) {
  // dragged + everything nested under it (can't drop a frame into its own child)
  const descendants = new Set<string>([dragged.id])
  let changed = true
  while (changed) {
    changed = false
    for (const n of nodes.value) {
      if (n.parentNode && descendants.has(n.parentNode) && !descendants.has(n.id)) { descendants.add(n.id); changed = true }
    }
  }
  const area = (n: FlowNode) => {
    const s = (n.style ?? {}) as Record<string, unknown>
    return (parseFloat(String(s.width ?? '0')) || 0) * (parseFloat(String(s.height ?? '0')) || 0)
  }
  const target = getIntersectingNodes(dragged)
    .filter(n => isGroup(n) && !descendants.has(n.id) && !(n.data as { collapsed?: boolean }).collapsed)
    .sort((a, b) => area(a) - area(b))[0]
  const targetId = target?.id ?? null
  if ((dragged.parentNode ?? null) === targetId) return
  const myAbs = absPos(dragged.id, dragged.position)
  if (targetId) {
    const par = absPos(targetId, { x: 0, y: 0 })
    updateNode(dragged.id, { parentNode: targetId, extent: 'parent', position: { x: myAbs.x - par.x, y: myAbs.y - par.y } })
  }
  else {
    updateNode(dragged.id, { parentNode: undefined, extent: undefined, position: { x: myAbs.x, y: myAbs.y } })
  }
  refreshMemberCounts()
}

function currentGraphJson(): string {
  return JSON.stringify(serializeGraph())
}
function seedHistory() {
  history.value = [currentGraphJson()]
  historyIndex.value = 0
}
function pushHistory() {
  clearDryRunVisualization() // the last dry run's painted path is stale once the graph changes
  if (historyTimer) clearTimeout(historyTimer)
  historyTimer = setTimeout(() => {
    const snap = currentGraphJson()
    if (history.value[historyIndex.value] === snap) return
    history.value = [...history.value.slice(0, historyIndex.value + 1), snap]
    historyIndex.value = history.value.length - 1
  }, 350)
}
function applySnapshot(snap: string) {
  const graph = JSON.parse(snap) as { nodes?: StoredNode[], edges?: StoredEdge[], groups?: StoredGroup[] }
  nodes.value = buildFlowNodes(graph.nodes ?? [], graph.groups ?? [])
  edges.value = (graph.edges ?? []).map(toFlowEdge)
  applyGroupVisibility()
  selectedNode.value = null
  selectedEdge.value = null
  dirty.value = true
}
function undo() {
  if (!canUndo.value) return
  historyIndex.value -= 1
  applySnapshot(history.value[historyIndex.value] ?? '{}')
}
function redo() {
  if (!canRedo.value) return
  historyIndex.value += 1
  applySnapshot(history.value[historyIndex.value] ?? '{}')
}

async function load() {
  loading.value = true
  await loadReferenceData()
  await loadSecrets()
  await loadSegments()
  await loadGitPipelines()
  await loadEnvironmentTypes()
  await loadMessageProjects()
  await loadNotificationTypes()
  if (isNew.value) {
    name.value = ''
    description.value = ''
    tags.value = []
    acceptedInputType.value = ''
    triggered.value = false
    endpointKey.value = ''
    apiEnabled.value = false
    isPublic.value = false
    scheduleEnabled.value = false
    schedule.value = ''
    maxConcurrentRuns.value = null
    maxRunsPerMinute.value = null
    version.value = 0
    gitRepositoryId.value = null
    gitPath.value = null
    lastSyncError.value = null
    nodes.value = [
      { id: 'input', type: 'pipeline', position: { x: 60, y: 160 }, data: { kind: 'input', label: 'Input', category: 'INPUT', settings: { acceptedType: '' } } },
    ]
    edges.value = []
    seedHistory()
    loading.value = false
    return
  }
  try {
    const res = await gqlQuery<{
      pipelines: { pipeline: { id: string, name: string, description: string, acceptedInputType: string, tags: string[], triggered: boolean, key: string, api: boolean, public: boolean, schedule: string | null, maxConcurrentRuns: number | null, maxRunsPerMinute: number | null, version: number, graph: unknown, gitRepositoryId: string | null, gitPath: string | null, lastSyncError: string | null } | null }
    }>(gql`
      query GetPipeline($id: UUID!) {
        pipelines { pipeline(id: $id) { id name description acceptedInputType tags triggered key api public schedule maxConcurrentRuns maxRunsPerMinute version graph gitRepositoryId gitPath lastSyncError } }
      }
    `, { id: pipelineId.value })
    const p = res?.pipelines?.pipeline
    if (!p) {
      toast.error('Pipeline not found')
      navigateTo('/pipelines/all')
      return
    }
    name.value = p.name
    description.value = p.description
    tags.value = p.tags ?? []
    triggered.value = p.triggered
    endpointKey.value = p.key
    apiEnabled.value = p.api
    isPublic.value = p.public
    schedule.value = p.schedule ?? ''
    scheduleEnabled.value = !!p.schedule
    maxConcurrentRuns.value = p.maxConcurrentRuns
    maxRunsPerMinute.value = p.maxRunsPerMinute
    version.value = p.version
    gitRepositoryId.value = p.gitRepositoryId
    gitPath.value = p.gitPath
    lastSyncError.value = p.lastSyncError
    // graph is a JSON scalar — it arrives as a parsed object.
    const graph = (p.graph ?? {}) as { nodes?: StoredNode[], edges?: StoredEdge[], groups?: StoredGroup[] }
    nodes.value = buildFlowNodes(graph.nodes ?? [], graph.groups ?? [])
    edges.value = (graph.edges ?? []).map(toFlowEdge)
    applyGroupVisibility()
    // After hydration so the row's value lands in the Input node (the source of truth),
    // reconciling any drift between the row and the stored graph.
    acceptedInputType.value = p.acceptedInputType
    seedHistory()
  }
  catch {
    toast.error('Failed to load the pipeline')
  }
  finally {
    loading.value = false
  }
}
onMounted(load)

// ─── Canvas interactions ─────────────────────────────────────────────────────────────────────
// A rejected drop is silent in Vue Flow (the edge is simply dropped). Track the drag's source
// handle and whether a valid edge formed, so onConnectEnd can explain a rejection to the user.
let connectFrom: { nodeId: string | null, handleId: string | null } | null = null
let connectMade = false
onConnect((connection: Connection) => {
  addEdges([{ ...connection, id: `e${Date.now().toString(36)}` }])
  connectMade = true
  dirty.value = true
  pushHistory()
})
onConnectStart(({ nodeId, handleId }) => {
  connectFrom = { nodeId: nodeId ?? null, handleId: handleId ?? null }
  connectMade = false
})
onConnectEnd((event) => {
  const from = connectFrom
  connectFrom = null
  // A valid edge was created — nothing to explain.
  if (connectMade || !from?.nodeId || !event) return
  // Resolve the handle the drag was released onto. If it isn't a target handle, the user dropped
  // on empty canvas (an abandoned drag, not a rejected mistake) — stay silent.
  const point = 'changedTouches' in event ? event.changedTouches[0] : event
  if (!point) return
  const el = document.elementFromPoint(point.clientX, point.clientY)
  const handle = el?.closest('.vue-flow__handle.target') as HTMLElement | null
  const targetNodeId = handle?.getAttribute('data-nodeid')
  if (!handle || !targetNodeId) return
  const reason = connectionRejection({
    source: from.nodeId,
    sourceHandle: from.handleId,
    target: targetNodeId,
    targetHandle: handle.getAttribute('data-handleid'),
  })
  if (reason) toast.error(reason)
})
onNodeClick(({ node }) => {
  selectedNode.value = node
  selectedEdge.value = null
})
onEdgeClick(({ edge }) => {
  selectedEdge.value = edge
  selectedNode.value = null
})
onPaneClick(() => {
  selectedNode.value = null
  selectedEdge.value = null
})
onNodeDragStop(({ node }) => {
  // Re-assign group membership by where the node landed (skip the structural Input/Output? no — any
  // node may be grouped). Groups themselves can be dragged into other groups (nesting).
  reparentOnDragStop(node as unknown as FlowNode)
  dirty.value = true
  pushHistory()
})

/**
 * Auto-arrange the canvas into a left-to-right layered layout (see computePipelineLayout). Each group
 * frame is treated as a single block: its members keep their in-frame arrangement and move with the
 * frame, and an edge into a framed node counts as an edge to that node's outermost frame — so a group
 * lands in the flow where its members connect. The editor does no layout on its own, so this is how a
 * seeded/synced/imported graph, or a hand-made tangle, gets cleaned up in one click.
 */
function autoLayout() {
  const topLevel = nodes.value.filter(n => !n.parentNode)
  if (topLevel.length < 2) return
  const parentOf = new Map(nodes.value.map(n => [n.id, n.parentNode as string | undefined]))
  const outerFrame = (id: string): string => {
    let cur = id
    for (let i = 0; i < 100 && parentOf.get(cur); i++) cur = parentOf.get(cur)!
    return cur
  }
  const topIds = new Set(topLevel.map(n => n.id))
  const blockSize = (n: FlowNode) => {
    if (n.type === GROUP_TYPE) {
      const style = (n.style ?? {}) as Record<string, unknown>
      const collapsed = (n.data as { collapsed?: boolean }).collapsed
      return {
        width: parseFloat(String(style.width ?? '300')) || 300,
        height: collapsed ? GROUP_HEADER_H : (parseFloat(String(style.height ?? '200')) || 200),
      }
    }
    const d = findNode(n.id)?.dimensions
    return { width: d?.width || 200, height: d?.height || 90 }
  }
  const positions = computePipelineLayout(
    topLevel.map(n => ({ id: n.id, ...blockSize(n) })),
    edges.value
      .map(e => ({ source: outerFrame(e.source), target: outerFrame(e.target) }))
      .filter(e => topIds.has(e.source) && topIds.has(e.target) && e.source !== e.target),
  )
  for (const [id, position] of positions) updateNode(id, { position })
  dirty.value = true
  pushHistory()
  // Recenter on the freshly arranged graph once Vue Flow has applied the new positions.
  nextTick(() => fitView({ padding: 0.2 }))
}

function addNode(t: NodeType, position?: { x: number, y: number }) {
  // Seed the node's settings from its declared defaults (non-blank defaults + required-no-default
  // placeholders, so the node deserializes). The Input node's accepted type is the one page-state
  // coupling that isn't expressible as a static default.
  const defaults = seedNodeDefaults(t.settings ?? [])
  if (t.key === 'input') defaults.acceptedType = acceptedInputType.value
  const node: FlowNode = {
    id: nextNodeId(),
    type: 'pipeline',
    position: position ?? { x: 240 + Math.random() * 120, y: 80 + Math.random() * 240 },
    // Carry the type's declared ports onto the node so the card renders one source handle per
    // output immediately — matching toFlowNode's load path. Without these a freshly-added node
    // shows a single anonymous handle until it's saved and reloaded.
    data: { kind: t.key, label: t.label, category: t.category, outputs: t.outputs, inputs: t.inputs, settings: defaults },
  }
  nodes.value = [...nodes.value, node]
  dirty.value = true
  pushHistory()
}

// Palette drag & drop: drop a node type anywhere on the canvas to add it at that spot.
function onPaletteDragStart(e: DragEvent, t: NodeType) {
  e.dataTransfer?.setData('application/x-pipeline-node', t.key)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}
function onCanvasDrop(e: DragEvent) {
  const key = e.dataTransfer?.getData('application/x-pipeline-node')
  if (!key) return
  const t = typeOf(key)
  if (!t) return
  addNode(t, screenToFlowCoordinate({ x: e.clientX, y: e.clientY }))
}

function removeSelected() {
  if (selectedNode.value) {
    const id = selectedNode.value.id
    // Removing the Input node removes the accepted type's home — keep the choice in the
    // fallback so the page value (and a re-added Input node) retains it.
    if (selectedNode.value.data.kind === 'input') acceptedInputTypeFallback.value = acceptedInputType.value
    nodes.value = nodes.value.filter(n => n.id !== id)
    edges.value = edges.value.filter(e => e.source !== id && e.target !== id)
    selectedNode.value = null
    dirty.value = true
  }
  else if (selectedEdge.value) {
    const id = selectedEdge.value.id
    edges.value = edges.value.filter(e => e.id !== id)
    selectedEdge.value = null
    dirty.value = true
  }
  pushHistory()
}

function setEdgePort(port: string) {
  if (!selectedEdge.value) return
  selectedEdge.value.label = port
  dirty.value = true
  pushHistory()
}

// A selected edge whose target declares input slots is wired to a specific port handle — its port is
// the handle name (not an editable label), so the inspector shows it read-only.
const selectedEdgeTargetDeclaresInputs = computed(() =>
  !!selectedEdge.value && nodeHasInputPorts(targetTypeOf(selectedEdge.value.target)))
const selectedEdgePort = computed(() => (selectedEdge.value ? edgeTargetPort(selectedEdge.value) : null))

// ─── Copy / paste: move a whole pipeline between editors — and across browsers — via the OS clipboard ──
// We serialise the SAME graph JSON the executor runs, wrapped with a marker so paste only accepts our
// own payloads. The OS clipboard (not an in-page variable) is what carries a pipeline from one browser
// to another. Paste re-mints every id and MERGES into the canvas (non-destructive): into a fresh
// pipeline it reproduces the source; into a populated one it composes onto what's already there.
const CLIPBOARD_MARKER = 'bosca.pipeline.clipboard/v1'
interface PipelineClipboard {
  marker: string
  name: string
  description: string
  acceptedInputType: string
  graph: { nodes: StoredNode[], edges: StoredEdge[], groups: StoredGroup[] }
}

async function copyPipeline() {
  if (!nodes.value.some(n => !isGroup(n))) {
    toast.error('Nothing to copy — the pipeline is empty')
    return
  }
  const payload: PipelineClipboard = {
    marker: CLIPBOARD_MARKER,
    name: name.value,
    description: description.value,
    acceptedInputType: acceptedInputType.value,
    graph: serializeGraph(),
  }
  try {
    await navigator.clipboard.writeText(JSON.stringify(payload))
    const count = payload.graph.nodes.length
    toast.success(`Copied pipeline (${count} node${count === 1 ? '' : 's'}) to the clipboard`)
  }
  catch {
    toast.error('Could not write to the clipboard — check your browser’s clipboard permission')
  }
}

function parseClipboardPipeline(text: string): PipelineClipboard | null {
  let parsed: unknown
  try { parsed = JSON.parse(text) }
  catch { return null }
  const p = parsed as Partial<PipelineClipboard> | null
  if (!p || p.marker !== CLIPBOARD_MARKER || !p.graph || !Array.isArray(p.graph.nodes)) return null
  return {
    marker: CLIPBOARD_MARKER,
    name: typeof p.name === 'string' ? p.name : '',
    description: typeof p.description === 'string' ? p.description : '',
    acceptedInputType: typeof p.acceptedInputType === 'string' ? p.acceptedInputType : '',
    graph: {
      nodes: p.graph.nodes,
      edges: Array.isArray(p.graph.edges) ? p.graph.edges : [],
      groups: Array.isArray(p.graph.groups) ? p.graph.groups : [],
    },
  }
}

/**
 * Merge a stored subgraph into the canvas with fresh ids. Structural endpoints are singletons: an
 * incoming Input/Output is dropped when the canvas already has one of that kind, and its edges are
 * rewired to the existing endpoint — so a pasted pipeline never mints a second Input (which would
 * break the acceptedInputType contract that reads from the one Input node). Returns the count of
 * functional (non-group) nodes added.
 */
function insertGraph(graph: { nodes: StoredNode[], edges: StoredEdge[], groups: StoredGroup[] }): number {
  // Offset a merge so pasted nodes don't land exactly on existing ones; into an empty canvas keep the
  // source coordinates so the result matches the original 1:1.
  const offset = nodes.value.length > 0 ? 48 : 0
  // The Input/Output endpoints already on the canvas — an incoming endpoint of the same kind reuses
  // these rather than minting a duplicate (the remap rewires its edges onto the existing node).
  const existingEndpoints = new Map<string, string>()
  for (const n of nodes.value) {
    const kind = String(n.data.kind)
    if ((kind === 'input' || kind === 'output') && !existingEndpoints.has(kind)) existingEndpoints.set(kind, n.id)
  }

  const remapped = remapPastedGraph(graph, { existingEndpoints, offset, nextNodeId, nextGroupId, nextEdgeId: () => uid('e') })

  // Nodes must be in the live list before edges are mapped — toFlowEdge resolves each target's input
  // ports from nodes.value (see targetTypeOf), matching the load and undo/redo paths.
  nodes.value = [...nodes.value, ...buildFlowNodes(remapped.nodes, remapped.groups)]
  edges.value = [...edges.value, ...remapped.edges.map(toFlowEdge)]
  applyGroupVisibility()
  refreshMemberCounts()
  selectedNode.value = null
  selectedEdge.value = null
  return remapped.nodes.length
}

async function pastePipeline() {
  let text: string
  try { text = await navigator.clipboard.readText() }
  catch {
    toast.error('Could not read the clipboard — check your browser’s clipboard permission')
    return
  }
  const payload = parseClipboardPipeline(text)
  if (!payload) {
    toast.error('The clipboard doesn’t contain a copied Bosca pipeline')
    return
  }
  const added = insertGraph(payload.graph)
  if (added === 0) {
    toast.error('Nothing to paste')
    return
  }
  // Seed identity into a still-blank pipeline (e.g. a brand-new one) without overwriting fields the
  // author has already filled in. acceptedInputType normally rides in the pasted Input node's settings;
  // the fallback only matters when no Input node came along.
  if (!name.value.trim() && payload.name.trim()) name.value = payload.name
  if (!description.value.trim() && payload.description.trim()) description.value = payload.description
  if (!acceptedInputType.value.trim() && payload.acceptedInputType.trim() && !nodes.value.some(n => n.data.kind === 'input')) {
    acceptedInputTypeFallback.value = payload.acceptedInputType
  }
  dirty.value = true
  pushHistory()
  toast.success(`Pasted ${added} node${added === 1 ? '' : 's'}`)
  nextTick(() => fitView({ padding: 0.2 }))
}

// Maximize: the canvas takes over the viewport; Esc restores. Vue Flow re-measures itself
// via its ResizeObserver, so no manual fit call is needed.
const maximized = ref(false)
function isTypingTarget(e: KeyboardEvent): boolean {
  const el = e.target as HTMLElement | null
  return !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)
}
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && maximized.value) {
    maximized.value = false
    return
  }
  if (isTypingTarget(e)) return
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'z') {
    e.preventDefault()
    if (e.shiftKey) redo()
    else undo()
  }
  else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'y') {
    e.preventDefault()
    redo()
  }
  else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'c') {
    // Leave a genuine text selection (e.g. a highlighted node label) to the browser's own copy.
    const sel = window.getSelection()
    if (sel && sel.toString().length > 0) return
    e.preventDefault()
    void copyPipeline()
  }
  else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'v') {
    e.preventDefault()
    void pastePipeline()
  }
  else if (e.key === 'Delete' || e.key === 'Backspace') {
    if (selectedNode.value || selectedEdge.value) {
      e.preventDefault()
      removeSelected()
    }
  }
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

// ─── Run history: the append-only run log of triggered executions ───────────────────────────
const runsOpen = ref(false)

// ─── Dry run: trace a real input through the SAVED graph, executing no actions ──────────────
const dryRunOpen = ref(false)
const dryRunPayload = ref('{}')
const dryRunning = ref(false)
const dryRunResult = ref<{
  outputs: Record<string, unknown>
  actions: Record<string, unknown>
  errors: Record<string, string>
  skipped: Record<string, string>
  error: string | null
} | null>(null)
// Entity pickers, matched against the accepted event's fqdn: picking an item fills the payload's
// `id`. Each loader returns (id, name) pairs for the dropdown.
interface IdName { id: string, name: string }
const ENTITY_PICKERS = [
  {
    match: /profile\.profile\.events\.Profile/,
    label: 'Pick a profile (fills the payload id)',
    query: gql`query DryRunProfiles { profiles { all(limit: 100, offset: 0) { id name } } }`,
    extract: (res: unknown) => (res as { profiles?: { all?: IdName[] } })?.profiles?.all ?? [],
  },
  {
    match: /profile\.organization\.events\.Organization/,
    label: 'Pick an organization (fills the payload id)',
    query: gql`query DryRunOrganizations { organizations { all(limit: 100, offset: 0) { id name } } }`,
    extract: (res: unknown) => (res as { organizations?: { all?: IdName[] } })?.organizations?.all ?? [],
  },
  {
    match: /\.events\.Collection/,
    label: 'Pick a collection (fills the payload id)',
    query: gql`query DryRunCollections { content { findCollections(query: { limit: 100, offset: 0 }) { id name } } }`,
    extract: (res: unknown) => (res as { content?: { findCollections?: IdName[] } })?.content?.findCollections ?? [],
  },
  {
    match: /\.events\.Metadata/,
    label: 'Pick a metadata item (fills the payload id)',
    query: gql`query DryRunMetadata { content { findMetadata(query: { limit: 100, offset: 0 }) { id name } } }`,
    extract: (res: unknown) => (res as { content?: { findMetadata?: IdName[] } })?.content?.findMetadata ?? [],
  },
]
const activePicker = computed(() => {
  // Only offer a picker when the event actually carries an `id` field to fill.
  const hasId = (eventFields.value.get(acceptedInputType.value) ?? []).some(f => f.name === 'id')
  if (!hasId) return undefined
  return ENTITY_PICKERS.find(p => p.match.test(acceptedInputType.value))
})
const pickerOptions = ref<{ value: string, label: string }[]>([])

/**
 * Payload skeleton for the run/dry-run editors: the event's catalogued fields, or — for
 * JSON-input pipelines — the Input node's declared schema properties.
 */
function buildPayloadSkeleton(): string {
  const skeleton: Record<string, unknown> = {}
  if (isJsonInput.value) {
    const input = nodes.value.find(n => n.data.kind === 'input')
    const schema = (input?.data.settings as Record<string, unknown> | undefined)?.schema as Record<string, unknown> | null | undefined
    const properties = (schema?.properties ?? {}) as Record<string, { type?: string }>
    for (const [field, prop] of Object.entries(properties)) {
      const t = prop?.type ?? 'string'
      skeleton[field] = t === 'boolean'
        ? false
        : t === 'number' || t === 'integer'
          ? 0
          : t === 'array' ? [] : t === 'object' ? {} : ''
    }
  }
  else {
    const fields = eventFields.value.get(acceptedInputType.value) ?? []
    for (const f of fields) {
      const t = f.type.replace('?', '')
      skeleton[f.name] = t === 'Boolean' ? false : ['Int', 'Long', 'Short', 'Double', 'Float'].includes(t) ? 0 : ''
    }
  }
  return JSON.stringify(skeleton, null, 2)
}

async function openDryRun() {
  dryRunResult.value = null
  dryRunPayload.value = buildPayloadSkeleton()
  dryRunOpen.value = true
  const picker = activePicker.value
  pickerOptions.value = []
  if (picker) {
    try {
      const res = await gqlQuery(picker.query)
      pickerOptions.value = picker.extract(res).map(e => ({ value: e.id, label: e.name || e.id }))
    }
    catch {
      // The picker is a convenience; the payload editor still works without it.
    }
  }
}

/** Merge the picked entity id into the payload, keeping any other skeleton fields. */
function pickDryRunEntity(entityId: string) {
  let payload: Record<string, unknown> = {}
  try {
    payload = JSON.parse(dryRunPayload.value) as Record<string, unknown>
  }
  catch {
    // Unparseable payload — replace it.
  }
  payload.id = entityId
  dryRunPayload.value = JSON.stringify(payload, null, 2)
}

// ─── Dry-run path visualization: paint the canvas from the last trace ────────────────────────
let dryRunVizActive = false
/** Paint nodes (ran / skipped / errored) and the edges along the path taken, from a dry-run trace. */
function applyDryRunVisualization(r: { outputs: Record<string, unknown>, actions: Record<string, unknown>, errors: Record<string, string>, skipped: Record<string, string> }) {
  const ran = new Set([...Object.keys(r.outputs), ...Object.keys(r.actions)])
  const skipped = new Set(Object.keys(r.skipped))
  const errored = new Set(Object.keys(r.errors))
  if (ran.size === 0 && skipped.size === 0 && errored.size === 0) return // nothing executed (pre-run failure)
  nodes.value = nodes.value.map(n => isGroup(n)
    ? n
    : { ...n, data: { ...n.data, runState: errored.has(n.id) ? 'error' : skipped.has(n.id) ? 'skipped' : ran.has(n.id) ? 'ran' : undefined } })
  edges.value = edges.value.map((e) => {
    const active = ran.has(e.source) && ran.has(e.target)
    const dim = skipped.has(e.source) || skipped.has(e.target)
    return { ...e, animated: active, class: active ? 'edge-active' : dim ? 'edge-dim' : undefined }
  })
  dryRunVizActive = true
}
/** Clear any dry-run painting — called on the next edit, when the trace is stale. */
function clearDryRunVisualization() {
  if (!dryRunVizActive) return
  dryRunVizActive = false
  nodes.value = nodes.value.map(n => (n.data as { runState?: string }).runState ? { ...n, data: { ...n.data, runState: undefined } } : n)
  edges.value = edges.value.map(e => (e.animated || e.class) ? { ...e, animated: false, class: undefined } : e)
}

async function runDryRun() {
  let payload: unknown
  try {
    payload = JSON.parse(dryRunPayload.value)
  }
  catch {
    toast.error('The event payload is not valid JSON')
    return
  }
  dryRunning.value = true
  dryRunResult.value = null
  clearDryRunVisualization()
  try {
    const res = await gqlMutation(gql`
      mutation DryRunPipeline($id: UUID!, $eventType: String, $payload: JSON!) {
        pipelines { dryRun(id: $id, eventType: $eventType, payload: $payload) { outputs actions errors skipped error } }
      }
    `, { id: pipelineId.value, eventType: isJsonInput.value ? null : acceptedInputType.value, payload })
    const raw = (res as { pipelines?: { dryRun?: { outputs: Record<string, unknown>, actions: Record<string, unknown>, errors: Record<string, string>, skipped: Record<string, string>, error: string | null } } } | null)?.pipelines?.dryRun
    if (!raw) throw new Error('The server returned an empty dry-run result')
    dryRunResult.value = {
      outputs: raw.outputs ?? {},
      actions: raw.actions ?? {},
      errors: raw.errors ?? {},
      skipped: raw.skipped ?? {},
      error: raw.error,
    }
  }
  catch (e) {
    // Pre-execution failures (payload doesn't decode as the event, no catalogued serializer, …)
    // arrive as GraphQL errors — surface the backend's message in the run-aborted area, where
    // long serialization messages stay readable.
    dryRunResult.value = {
      outputs: {},
      actions: {},
      errors: {},
      skipped: {},
      error: e instanceof Error ? e.message : 'Dry run failed',
    }
  }
  finally {
    dryRunning.value = false
  }
  if (dryRunResult.value) applyDryRunVisualization(dryRunResult.value)
}

// ─── Manual run: like dry run, but FOR REAL — action nodes execute their side effects ────────
const runOpen = ref(false)
const runPayload = ref('{}')
const running = ref(false)
const runResult = ref<{ ok: boolean, output: unknown, error: string | null } | null>(null)

function openRun() {
  runResult.value = null
  runPayload.value = buildPayloadSkeleton()
  runOpen.value = true
}

async function runNow() {
  let payload: unknown
  try {
    payload = JSON.parse(runPayload.value)
  }
  catch {
    toast.error('The payload is not valid JSON')
    return
  }
  running.value = true
  runResult.value = null
  try {
    const res = await gqlMutation(gql`
      mutation RunPipeline($id: UUID!, $payload: JSON!) {
        pipelines { run(id: $id, payload: $payload) { ok output error } }
      }
    `, { id: pipelineId.value, payload })
    const raw = (res as { pipelines?: { run?: { ok: boolean, output: unknown, error: string | null } } } | null)?.pipelines?.run
    if (!raw) throw new Error('The server returned an empty run result')
    runResult.value = raw
    // On success there's nothing left to do in the dialog — the run is recorded in Run history (the
    // "Runs" panel, where its per-node output is browsable). Dismiss instead of parking on the result;
    // a failed run keeps the dialog open so its error stays in view next to the payload.
    if (raw.ok) {
      toast.success(raw.output === null || raw.output === undefined
        ? 'Pipeline run completed'
        : 'Pipeline run completed — see Runs for the output')
      runOpen.value = false
    }
  }
  catch (e) {
    runResult.value = { ok: false, output: null, error: e instanceof Error ? e.message : 'Run failed' }
  }
  finally {
    running.value = false
  }
}

/** Nodes in display order with their dry-run trace, for the results list. */
const dryRunRows = computed(() => {
  const r = dryRunResult.value
  if (!r) return []
  return nodes.value.map((n) => {
    const settings = n.data.settings as Record<string, unknown>
    const name = typeof settings.name === 'string' && settings.name.trim().length > 0 ? settings.name : String(n.data.label)
    return {
      id: n.id,
      name,
      category: String(n.data.category),
      output: r.outputs[n.id] !== undefined ? JSON.stringify(r.outputs[n.id], null, 2) : null,
      action: r.actions[n.id] !== undefined ? JSON.stringify(r.actions[n.id], null, 2) : null,
      error: r.errors[n.id] ?? null,
      skipped: r.skipped[n.id] ?? null,
    }
  })
})

// The inspector mutates node settings in place while `nodes` is a shallowRef — nudge it so
// computeds over node settings (acceptedInputType, and everything derived from it) re-evaluate.
function onInspectorChange() {
  triggerRef(nodes)
  dirty.value = true
  pushHistory()
}

// ─── Save ────────────────────────────────────────────────────────────────────────────────────
const valid = computed(() => name.value.trim().length > 0 && acceptedInputType.value.trim().length > 0)

async function save() {
  if (!valid.value) {
    toast.error('A pipeline needs a name and an accepted event')
    return
  }
  if (apiEnabled.value && endpointKey.value.trim().length === 0) {
    toast.error('An API-callable pipeline needs an endpoint key')
    return
  }
  saving.value = true
  try {
    const graph = serializeGraph()
    const vars: { input: Record<string, unknown>, authorName?: string, authorEmail?: string } = {
      input: {
        id: isNew.value ? null : pipelineId.value,
        name: name.value.trim(),
        description: description.value.trim(),
        acceptedInputType: acceptedInputType.value,
        tags: tags.value,
        triggered: triggered.value,
        key: endpointKey.value.trim(),
        api: apiEnabled.value,
        public: isPublic.value,
        schedule: scheduleEnabled.value && schedule.value.trim() ? schedule.value.trim() : null,
        maxConcurrentRuns: maxConcurrentRuns.value,
        maxRunsPerMinute: maxRunsPerMinute.value,
        version: version.value,
        graph,
      },
    }
    if (gitLinked.value) {
      vars.authorName = commitAuthor.value.authorName
      vars.authorEmail = commitAuthor.value.authorEmail
    }
    const res = await gqlMutation(gql`
      mutation SavePipeline($input: PipelineInput!, $authorName: String, $authorEmail: String) {
        pipelines { save(input: $input, authorName: $authorName, authorEmail: $authorEmail) { id version } }
      }
    `, vars)
    const saved = (res as { pipelines?: { save?: { id: string, version: number } } } | null)?.pipelines?.save
    toast.success('Pipeline saved')
    dirty.value = false
    if (isNew.value && saved?.id) {
      navigateTo(`/pipelines/${saved.id}`, { replace: true })
    }
    else if (saved) {
      version.value = saved.version
    }
    if (gitLinked.value) {
      await refreshGitState()
    }
  }
  catch {
    toast.error('Failed to save the pipeline')
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Pipelines', isNew ? 'New Pipeline' : name || 'Pipeline')"
        :title="isNew ? 'New Pipeline' : name || 'Pipeline'"
        subtitle="Wire nodes left-to-right: input → transforms → actions/output"
      >
        <template #actions>
          <Button
            size="sm"
            @click="navigateTo('/pipelines/all')"
          >
            Back
          </Button>
          <Button
            size="sm"
            icon="history"
            title="Run history — every triggered execution of this pipeline"
            :disabled="isNew"
            @click="runsOpen = true"
          >
            Runs
          </Button>
          <Button
            v-if="!isNew && !loading && !gitLinked"
            size="sm"
            icon="git-branch"
            title="Serialize this pipeline as a YAML file in a Git repository"
            :accent="accent"
            @click="showLinkModal = true"
          >
            Link to Git
          </Button>
          <Button
            primary
            icon="check"
            size="sm"
            :accent="accent"
            :loading="saving"
            :disabled="!valid || (!dirty && !isNew)"
            :title="!valid ? 'A pipeline needs a name and an input — the event it accepts, or JSON' : undefined"
            @click="save"
          >
            Save
          </Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="lastSyncError" class="sync-error-banner">
      <strong>Git sync error:</strong> {{ lastSyncError }}
    </div>
    <div v-else-if="gitLinked" class="git-linked-banner">
      <Icon name="git" :size="12" :color="accent" />
      Linked to Git: <code class="mono">{{ gitPath }}</code>
    </div>

    <SectionCard
      title="Details"
      padded
    >
      <div class="details-form">
        <div class="field-grid">
          <TextInput
            :model-value="name"
            label="Name"
            placeholder="e.g. Profile created → HubSpot"
            @update:model-value="(v: string) => { name = v; dirty = true }"
          />
          <Select
            :model-value="acceptedInputType"
            :options="inputTypeOptions"
            label="Input"
            searchable
            placeholder="The event this pipeline accepts, or JSON…"
            @update:model-value="(v: string | string[] | null | undefined) => { if (typeof v === 'string') { acceptedInputType = v; dirty = true } }"
          />
          <TextInput
            :model-value="description"
            label="Description"
            placeholder="What this pipeline does"
            @update:model-value="(v: string) => { description = v; dirty = true }"
          />
          <TagInput
            :model-value="tags"
            label="Tags"
            placeholder="Add a tag to group and filter by…"
            @update:model-value="(v: string[]) => { tags = v; dirty = true }"
          />
        </div>

        <div class="settings-grid">
          <div class="setting">
            <span class="field-label">Active</span>
            <Switch
              :model-value="triggered && !isJsonInput"
              :disabled="isJsonInput"
              :label="isJsonInput
                ? 'JSON-input pipelines run via Run Pipeline nodes or manual runs, not events'
                : 'runs automatically when the event fires'"
              @update:model-value="(v: boolean) => { triggered = v; dirty = true }"
            />
          </div>

          <div class="setting">
            <span class="field-label">Schedule</span>
            <Switch
              :model-value="scheduleEnabled"
              label="run on a cron schedule"
              @update:model-value="(v: boolean) => { scheduleEnabled = v; dirty = true }"
            />
            <template v-if="scheduleEnabled">
              <TextInput
                :model-value="schedule"
                label="Cron expression"
                mono
                placeholder="e.g. 0 9 * * * (every day at 09:00)"
                @update:model-value="(v: string) => { schedule = v; dirty = true }"
              />
              <p class="hint">
                Standard 5-field cron (minute hour day-of-month month day-of-week). The run is seeded with
                the fire time and recorded in run history as a <code class="mono">schedule</code> run.
              </p>
            </template>
          </div>

          <div class="setting">
            <span class="field-label">Run limits</span>
            <TextInput
              :model-value="maxConcurrentRuns?.toString() ?? ''"
              label="Max concurrent runs"
              placeholder="unlimited"
              @update:model-value="(v: string) => { maxConcurrentRuns = parseLimit(v); dirty = true }"
            />
            <TextInput
              :model-value="maxRunsPerMinute?.toString() ?? ''"
              label="Max runs per minute"
              placeholder="unlimited"
              @update:model-value="(v: string) => { maxRunsPerMinute = parseLimit(v); dirty = true }"
            />
            <p class="hint">
              Caps protect downstream systems from a trigger storm. Blank = unlimited;
              excess triggered/scheduled runs are shed (logged), not queued.
            </p>
          </div>

          <div class="setting">
            <span class="field-label">API endpoint</span>
            <Switch
              :model-value="apiEnabled"
              label="callable via REST"
              @update:model-value="(v: boolean) => { apiEnabled = v; dirty = true }"
            />
            <template v-if="apiEnabled">
              <TextInput
                :model-value="endpointKey"
                label="Endpoint key"
                mono
                placeholder="e.g. send-welcome-email"
                @update:model-value="(v: string) => { endpointKey = v; dirty = true }"
              />
              <div class="subfield">
                <span class="field-label">Access</span>
                <Switch
                  :model-value="isPublic"
                  label="public — no EXECUTE grant required"
                  @update:model-value="(v: boolean) => { isPublic = v; dirty = true }"
                />
              </div>
              <p
                v-if="endpointKey.trim()"
                class="hint endpoint-hint"
              >
                POST or GET <code class="mono">/api/v1/p/{{ endpointKey.trim() }}</code> — the request
                body (or query parameters) is the pipeline's input; the Output node's value is the
                response.
              </p>
            </template>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard
      title="Graph"
      subtitle="Add nodes from the palette, connect them, and configure each node in the inspector."
    >
      <template #right>
        <div class="header-controls">
          <Button
            size="xs"
            icon="play"
            :title="dirty && !isNew
              ? 'Dry run uses the saved graph — save your changes first'
              : 'Dry run — trace an input through the saved graph without executing actions'"
            :disabled="isNew || dirty"
            @click="openDryRun"
          >
            Dry Run
          </Button>
          <Button
            size="xs"
            icon="zap"
            :title="dirty && !isNew
              ? 'Run uses the saved graph — save your changes first'
              : 'Run the saved graph now, for real — action nodes execute their side effects'"
            :disabled="isNew || dirty"
            @click="openRun"
          >
            Run
          </Button>
          <Button
            size="xs"
            icon="workflow"
            title="Auto-arrange the layout"
            :disabled="nodes.length < 2"
            @click="autoLayout"
          />
          <Button
            size="xs"
            icon="copy"
            title="Copy pipeline (⌘C)"
            :disabled="nodes.length === 0"
            @click="copyPipeline"
          />
          <Button
            size="xs"
            icon="clipboard-paste"
            title="Paste pipeline (⌘V)"
            @click="pastePipeline"
          />
          <Button
            size="xs"
            icon="undo"
            title="Undo (⌘Z)"
            :disabled="!canUndo"
            @click="undo"
          />
          <Button
            size="xs"
            icon="redo"
            title="Redo (⇧⌘Z)"
            :disabled="!canRedo"
            @click="redo"
          />
          <Button
            size="xs"
            icon="maximize"
            title="Maximize"
            @click="maximized = true"
          />
        </div>
      </template>
      <div
        class="editor-layout"
        :class="{ maximized }"
      >
        <!-- In maximize mode the card header (with its controls) is covered, so the overlay
             carries its own cluster in the upper-right corner of the screen. -->
        <div
          v-if="maximized"
          class="editor-controls"
        >
          <Button
            size="xs"
            icon="workflow"
            title="Auto-arrange the layout"
            :disabled="nodes.length < 2"
            @click="autoLayout"
          />
          <Button
            size="xs"
            icon="copy"
            title="Copy pipeline (⌘C)"
            :disabled="nodes.length === 0"
            @click="copyPipeline"
          />
          <Button
            size="xs"
            icon="clipboard-paste"
            title="Paste pipeline (⌘V)"
            @click="pastePipeline"
          />
          <Button
            size="xs"
            icon="undo"
            title="Undo (⌘Z)"
            :disabled="!canUndo"
            @click="undo"
          />
          <Button
            size="xs"
            icon="redo"
            title="Redo (⇧⌘Z)"
            :disabled="!canRedo"
            @click="redo"
          />
          <Button
            size="xs"
            icon="minimize"
            title="Restore (Esc)"
            @click="maximized = false"
          />
        </div>
        <aside class="palette">
          <TextInput
            v-model="paletteFilter"
            type="search"
            size="sm"
            icon="search"
            placeholder="Search nodes…"
          />
          <Button
            size="xs"
            variant="ghost"
            icon="square-dashed"
            class="add-group-btn"
            title="Add a group frame, then drop nodes inside it"
            @click="addGroup"
          >
            Add group
          </Button>
          <Button
            size="xs"
            variant="ghost"
            icon="boxes"
            class="add-group-btn"
            title="Browse every node the editor can place — grouped, searchable, with full slot and setting reference"
            @click="nodeBrowserOpen = true"
          >
            Browse nodes
          </Button>
          <Button
            v-if="browsableTypes.length"
            size="xs"
            variant="ghost"
            icon="braces"
            class="add-group-btn"
            title="Browse the object types that flow between nodes"
            @click="openTypeBrowser()"
          >
            Browse types
          </Button>
          <p
            v-if="paletteFilter.trim() && paletteGroups.length === 0"
            class="hint"
          >
            No nodes match.
          </p>
          <div
            v-for="group in paletteGroups"
            :key="group.name"
            class="palette-group"
          >
            <div class="palette-title">
              {{ group.name }}
            </div>
            <template
              v-for="sub in group.subgroups"
              :key="sub.name ?? ''"
            >
              <div
                v-if="sub.name"
                class="palette-subtitle"
              >
                {{ sub.name }}
              </div>
              <Popover
                v-for="t in sub.types"
                :key="t.key"
                trigger="mouseenter"
                placement="right"
                :interactive="false"
                :delay="250"
              >
                <template #trigger>
                  <button
                    type="button"
                    class="palette-item"
                    draggable="true"
                    @dragstart="onPaletteDragStart($event, t)"
                    @click="addNode(t)"
                  >
                    <Icon
                      :name="pipelineNodeIcon(t.key, t.category)"
                      :size="13"
                      :color="pipelineNodeAccent(t.category)"
                      class="palette-icon"
                    />
                    <span class="palette-label">{{ t.label }}</span>
                  </button>
                </template>
                <div class="palette-tip">
                  <div class="palette-tip-head">
                    <Icon
                      :name="pipelineNodeIcon(t.key, t.category)"
                      :size="16"
                      :color="pipelineNodeAccent(t.category)"
                      class="palette-tip-icon"
                    />
                    <span class="palette-tip-name">{{ t.label }}</span>
                    <span class="palette-tip-category">{{ t.category }}</span>
                  </div>
                  <p
                    v-if="t.description"
                    class="palette-tip-desc"
                  >
                    {{ t.description }}
                  </p>
                  <ul
                    v-if="t.inputs.length"
                    class="palette-tip-slots"
                  >
                    <li
                      v-for="s in t.inputs"
                      :key="s.name"
                      class="palette-tip-slot"
                    >
                      <span class="palette-tip-slot-arrow">in</span>
                      <span class="palette-tip-slot-name">{{ s.name }}</span>
                      <span class="palette-tip-slot-kind">{{ slotConstraintLabel(s) }}</span>
                      <span
                        v-if="!s.required"
                        class="palette-tip-slot-opt"
                      >optional</span>
                    </li>
                  </ul>
                  <ul
                    v-if="t.outputs.length"
                    class="palette-tip-slots"
                  >
                    <li
                      v-for="o in t.outputs"
                      :key="o.name"
                      class="palette-tip-slot"
                    >
                      <span class="palette-tip-slot-arrow">out</span>
                      <span
                        class="palette-tip-slot-name"
                        :class="{ 'is-error': o.error }"
                      >{{ o.name }}</span>
                      <span class="palette-tip-slot-kind">{{ o.typeLabel || o.kind.toLowerCase() }}</span>
                      <span
                        v-if="o.error"
                        class="palette-tip-slot-err"
                      >error</span>
                    </li>
                  </ul>
                </div>
              </Popover>
            </template>
          </div>
        </aside>

        <ClientOnly>
          <div
            class="canvas"
            @drop="onCanvasDrop"
            @dragover.prevent
          >
            <VueFlow
              v-model:nodes="nodes"
              v-model:edges="edges"
              fit-view-on-init
              :min-zoom="0.3"
              :max-zoom="2"
              :delete-key-code="null"
              :is-valid-connection="isValidConnection"
            >
              <template #node-pipeline="props">
                <PipelineNodeCard
                  :data="props.data"
                  :selected="props.selected"
                  :peek-pipeline-id="nodePeekTarget(props.data)"
                  @peek="(id: string) => { peekTargetId = id }"
                />
              </template>
              <template #node-group="props">
                <PipelineGroupNode
                  :id="props.id"
                  :data="props.data"
                  :selected="props.selected"
                  @update:label="(v: string) => setGroupLabel(props.id, v)"
                  @toggle-collapse="() => toggleGroupCollapsed(props.id)"
                  @remove="() => removeGroup(props.id)"
                />
              </template>
            </VueFlow>
          </div>
          <template #fallback>
            <div class="canvas canvas-loading">
              Loading editor…
            </div>
          </template>
        </ClientOnly>

        <aside class="inspector-pane">
          <template v-if="selectedGroup">
            <h4>Group</h4>
            <TextInput
              :model-value="selectedGroup?.data.label ?? ''"
              label="Name"
              placeholder="Name this group…"
              @update:model-value="renameSelectedGroup"
            />
            <TextInput
              :model-value="selectedGroup?.data.description ?? ''"
              label="Description"
              placeholder="What this group is for"
              @update:model-value="describeSelectedGroup"
            />
            <p class="hint">
              A group is a visual frame only — it organizes the canvas and is ignored when the pipeline runs.
            </p>
            <Button
              size="xs"
              icon="trash"
              danger
              @click="removeSelectedGroup"
            >
              Remove group
            </Button>
          </template>
          <template v-else-if="selectedNode">
            <PipelineNodeInspector
              :key="selectedNode.id"
              :data="selectedNode.data"
              :settings="selectedNodeSettings"
              :expects="selectedNodeInputs"
              :produces="selectedNodeProduces"
              :scripts="scriptOptions"
              :jobs="jobOptions"
              :events="eventOptions"
              :pipelines="pipelineOptions"
              :git-pipelines="gitPipelineOptions"
              :environment-types="environmentTypeOptions"
              :message-projects="messageProjects"
              :notification-types="notificationTypeOptions"
              :search-indexes="searchIndexOptions"
              :attribute-types="attributeTypeOptions"
              :secrets="secretOptions"
              :segments="segmentOptions"
              :types="castTypes"
              :detected-shape="selectedNodeDetectedShape"
              @change="onInspectorChange"
            />
            <Button
              size="xs"
              icon="trash"
              danger
              @click="removeSelected"
            >
              Remove node
            </Button>
          </template>
          <template v-else-if="selectedEdge">
            <h4>Edge</h4>
            <div
              v-if="selectedEdgeTargetDeclaresInputs"
              class="edge-port"
            >
              <span class="edge-port-label">Input port</span>
              <code class="mono">{{ selectedEdgePort || '—' }}</code>
              <p class="hint">
                Set by the input handle this edge connects to. Re-wire it to a different handle to change the port.
              </p>
            </div>
            <TextInput
              v-else
              :model-value="typeof selectedEdge.label === 'string' ? selectedEdge.label : ''"
              label="Port name (keys fan-in on Combine nodes)"
              placeholder="e.g. profile"
              @update:model-value="setEdgePort"
            />
            <Button
              size="xs"
              icon="trash"
              danger
              @click="removeSelected"
            >
              Remove edge
            </Button>
          </template>
          <p
            v-else
            class="hint"
          >
            Select a node or edge to configure it.
          </p>
        </aside>
      </div>
    </SectionCard>

    <PipelineRunsModal
      v-if="runsOpen"
      :pipeline-id="pipelineId"
      :pipeline-name="name"
      @close="runsOpen = false"
    />

    <PipelineLinkToGitModal
      v-if="showLinkModal && !isNew"
      :pipeline-id="pipelineId"
      :pipeline-name="name"
      :accent="accent"
      @close="showLinkModal = false"
      @linked="onLinked"
    />

    <PipelineTypeBrowser
      v-if="typeBrowserOpen"
      :types="browsableTypes"
      :initial="typeBrowserInitial"
      @close="typeBrowserOpen = false"
    />

    <PipelineNodeBrowser
      v-if="nodeBrowserOpen"
      :types="nodeTypes"
      @add="(t) => { addNode(t); nodeBrowserOpen = false }"
      @close="nodeBrowserOpen = false"
    />

    <PipelinePeekModal
      v-if="peekTargetId"
      :pipeline-id="peekTargetId"
      :pipeline-name="peekTargetName"
      @close="peekTargetId = null"
    />

    <Modal
      v-if="dryRunOpen"
      title="Dry Run"
      subtitle="Traces the input through the saved graph — actions record what they would do, nothing executes"
      icon="play"
      width="min(90vw, 1000px)"
      @close="dryRunOpen = false"
    >
      <div class="dry-run">
        <p
          v-if="dirty"
          class="hint warn"
        >
          You have unsaved changes — the dry run uses the last saved version of the graph.
        </p>
        <Select
          v-if="activePicker && pickerOptions.length"
          :model-value="''"
          :options="pickerOptions"
          :label="activePicker.label"
          searchable
          placeholder="Choose…"
          @update:model-value="(v: string | string[] | null | undefined) => typeof v === 'string' && pickDryRunEntity(v)"
        />
        <CodeEditor
          :model-value="dryRunPayload"
          label="Event payload (JSON)"
          language="json"
          :rows="6"
          @update:model-value="(v: string) => dryRunPayload = v"
        />
        <div>
          <Button
            primary
            size="xs"
            icon="play"
            :accent="accent"
            :loading="dryRunning"
            @click="runDryRun"
          >
            Run
          </Button>
        </div>

        <template v-if="dryRunResult">
          <p
            v-if="dryRunResult.error"
            class="hint error-text"
          >
            Run aborted: {{ dryRunResult.error }}
          </p>
          <div
            v-for="row in dryRunRows"
            :key="row.id"
            class="trace-row"
            :class="{ 'trace-error': row.error, 'trace-skipped': row.skipped || (!row.output && !row.action && !row.error) }"
          >
            <div class="trace-head">
              <span class="trace-category">{{ row.category }}</span>
              <span class="trace-name">{{ row.name }}</span>
              <span
                v-if="row.action"
                class="trace-badge"
              >{{ row.category === 'ROUTE' ? 'routed' : 'would execute (skipped)' }}</span>
              <span
                v-else-if="row.error"
                class="trace-badge trace-badge-error"
              >failed</span>
              <span
                v-else-if="row.skipped"
                class="trace-badge trace-badge-skipped"
                :title="row.skipped"
              >skipped — branch not taken</span>
              <span
                v-else-if="!row.output"
                class="trace-badge"
              >no output</span>
            </div>
            <pre
              v-if="row.output"
              class="trace-pre"
            >{{ row.output }}</pre>
            <pre
              v-if="row.action"
              class="trace-pre trace-action"
            >{{ row.action }}</pre>
            <p
              v-if="row.error"
              class="hint error-text"
            >
              {{ row.error }}
            </p>
          </div>
        </template>
      </div>
    </Modal>

    <Modal
      v-if="runOpen"
      title="Run Pipeline"
      subtitle="Runs the saved graph now, for real — action nodes execute their side effects"
      icon="zap"
      width="min(90vw, 800px)"
      @close="runOpen = false"
    >
      <div class="dry-run">
        <p
          v-if="dirty"
          class="hint warn"
        >
          You have unsaved changes — the run uses the last saved version of the graph.
        </p>
        <CodeEditor
          :model-value="runPayload"
          :label="isJsonInput ? 'Input (JSON — validated against the input schema)' : 'Event payload (JSON)'"
          language="json"
          :rows="6"
          @update:model-value="(v: string) => runPayload = v"
        />
        <div>
          <Button
            primary
            size="xs"
            icon="zap"
            :accent="accent"
            :loading="running"
            @click="runNow"
          >
            Run now
          </Button>
        </div>

        <template v-if="runResult">
          <p
            v-if="runResult.error"
            class="hint error-text"
          >
            Run failed: {{ runResult.error }}
          </p>
          <template v-else>
            <p class="hint">
              Run completed{{ runResult.output === null || runResult.output === undefined ? ' — no output (the pipeline has no Output node or nothing reached it)' : '' }}.
            </p>
            <pre
              v-if="runResult.output !== null && runResult.output !== undefined"
              class="trace-pre"
            >{{ JSON.stringify(runResult.output, null, 2) }}</pre>
          </template>
        </template>
      </div>
    </Modal>
  </PageShell>
</template>

<style scoped>
.sync-error-banner {
  background: color-mix(in oklch, var(--err) 12%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--err) 30%, transparent);
  color: var(--fg-1);
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
}
.git-linked-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: color-mix(in oklch, var(--brand-2) 6%, var(--bg-2));
  border: 1px solid color-mix(in oklch, var(--line) 60%, transparent);
  font-size: 12.5px;
  color: var(--fg-2);
}
.git-linked-banner code { color: var(--fg-1); }
.details-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
/* Identity fields (name / input / description) — homogeneous single-row controls. */
.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}
/* Settings groups (active / schedule / run limits / api). Each cell is a self-contained
   group whose conditional detail nests inside it, so a toggle and the field it reveals stay
   together. align-items:start keeps short groups from stretching to a tall neighbour. */
.settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(248px, 1fr));
  gap: 20px;
  align-items: start;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}
/* Grid items default to min-width:auto, letting long values push fields past their column —
   and the shared Select also applies an inline minWidth sized to its widest option label. */
.field-grid > *,
.settings-grid > * {
  min-width: 0;
}
.field-grid :deep(.select-trigger) {
  min-width: 0 !important;
  max-width: 100%;
}
.setting {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.setting .subfield {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}
.endpoint-hint code {
  color: var(--fg-1);
}
/* Dry-run path: the edges along the taken path are accented + animated; not-taken branches fade. */
:deep(.vue-flow__edge.edge-active .vue-flow__edge-path) {
  stroke: var(--accent, #f59e0b);
  stroke-width: 2.5;
}
:deep(.vue-flow__edge.edge-dim) {
  opacity: 0.25;
}

.editor-layout {
  display: grid;
  grid-template-columns: 225px minmax(0, 1fr) 320px;
  /* One uniform inset everywhere (the card's padded body mixes 16/18px, so we own the padding). */
  gap: 16px;
  padding: 16px;
  /* Fill the rest of the viewport below the header + details card. */
  height: calc(100vh - 380px);
  min-height: 480px;
}
.editor-layout > * {
  min-width: 0;
}
.editor-layout.maximized {
  position: fixed;
  inset: 0;
  z-index: 1000;
  height: auto;
  margin: 0;
  padding: 16px;
  background: var(--bg, #0b1020);
}
.header-controls {
  display: flex;
  gap: 8px;
}
.editor-controls {
  position: fixed;
  top: 16px;
  right: 16px;
  z-index: 1001;
  display: flex;
  gap: 8px;
}
/* Keep the inspector's first rows clear of the fixed control cluster. */
.editor-layout.maximized .inspector-pane {
  padding-top: 44px;
}
.palette {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}
.palette-title {
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--text-muted, #94a3b8);
  margin-bottom: 4px;
}
/* Second-level taxonomy heading (e.g. Releases under WorkOps) — quieter than the group title. */
.palette-subtitle {
  font-size: 9px;
  letter-spacing: 0.06em;
  color: var(--text-muted, #94a3b8);
  opacity: 0.75;
  margin: 6px 0 2px 6px;
}
/* Each palette item is wrapped by a Popover trigger span — stretch it so items stay full-width. */
.palette-group :deep(.popover-trigger) {
  display: block;
  width: 100%;
}
.palette-item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin-bottom: 4px;
  padding: 6px 8px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.2));
  border-radius: 6px;
  background: var(--surface-raised, rgba(15, 23, 42, 0.6));
  color: var(--text, #e2e8f0);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.palette-item:hover {
  border-color: var(--accent, #f59e0b);
}
.palette-icon {
  flex-shrink: 0;
  color: var(--text-muted, #94a3b8);
}
.palette-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* Rich hover card (teleported to body by the Popover, so scoped via the data-v attribute). */
.palette-tip {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: 280px;
}
.palette-tip-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.palette-tip-icon {
  flex-shrink: 0;
  color: var(--accent, #f59e0b);
}
.palette-tip-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text, #e2e8f0);
}
.palette-tip-category {
  margin-left: auto;
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--text-muted, #94a3b8);
}
.palette-tip-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted, #94a3b8);
}
.palette-tip-slots {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.palette-tip-slot {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 11px;
}
.palette-tip-slot-arrow {
  font-size: 8px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-muted, #94a3b8);
  opacity: 0.7;
  width: 18px;
}
.palette-tip-slot-name {
  color: var(--text, #e2e8f0);
  font-weight: 500;
}
.palette-tip-slot-name.is-error {
  color: var(--danger, #f87171);
}
.palette-tip-slot-kind {
  color: var(--text-muted, #94a3b8);
  font-family: var(--font-mono, ui-monospace, monospace);
}
.palette-tip-slot-opt {
  margin-left: auto;
  font-size: 9px;
  letter-spacing: 0.06em;
  color: var(--text-muted, #94a3b8);
}
.palette-tip-slot-err {
  margin-left: auto;
  font-size: 9px;
  letter-spacing: 0.06em;
  color: var(--danger, #f87171);
}
.canvas {
  position: relative;
  height: 100%;
  min-height: 420px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.2));
  border-radius: 8px;
  overflow: hidden;
}
.canvas-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted, #94a3b8);
  font-size: 12px;
}
.inspector-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}
.inspector-pane h4 {
  margin: 0;
  font-size: 13px;
}
.hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted, #94a3b8);
}
.edge-port {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.edge-port-label {
  font-size: 12px;
  font-weight: 550;
  color: var(--fg-2);
}
.dry-run {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dry-run .warn {
  color: var(--warning, #fbbf24);
}
.error-text {
  color: var(--danger, #f87171);
}
.trace-row {
  padding: 8px 10px;
  border: 1px solid var(--border, rgba(148, 163, 184, 0.2));
  border-radius: 8px;
}
.trace-row.trace-error {
  border-color: var(--danger, #f87171);
}
.trace-row.trace-skipped {
  opacity: 0.6;
}
.trace-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.trace-category {
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--text-muted, #94a3b8);
}
.trace-name {
  font-size: 12px;
  font-weight: 500;
}
.trace-badge {
  margin-left: auto;
  font-size: 10px;
  color: var(--text-muted, #94a3b8);
}
.trace-badge-error {
  color: var(--danger, #f87171);
}
.trace-badge-skipped {
  color: var(--warning, #fbbf24);
}
.trace-pre {
  margin: 8px 0 0;
  padding: 8px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.6);
  font-size: 11px;
  max-height: 180px;
  overflow: auto;
}
.trace-action {
  border-left: 2px solid var(--warning, #fbbf24);
}
</style>
