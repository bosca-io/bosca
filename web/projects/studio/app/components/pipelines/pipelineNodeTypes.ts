/**
 * Shared TypeScript shapes for the pipeline node-type palette (`pipelines.nodeTypes`). These mirror the
 * backend `NodeDescriptor` projection and are the single source the editor's palette, inspector, and
 * generic settings renderer read — so a new node type renders its whole form with no UI change.
 */

/** Editor control a setting renders as — mirrors the backend `SettingControl` enum. */
export type SettingControl =
  | 'TEXT' | 'TEXTAREA' | 'INTEGER' | 'NUMBER' | 'BOOLEAN' | 'ENUM'
  | 'CODE' | 'SCHEMA' | 'CONDITION' | 'REFERENCE' | 'LIST' | 'GROUP_LIST'

/** The server-loaded list a REFERENCE setting picks from — mirrors the backend `ReferenceSource` enum. */
export type ReferenceSource = 'JOB' | 'SCRIPT' | 'PIPELINE' | 'EVENT' | 'ATTRIBUTE_TYPE' | 'SEARCH_INDEX' | 'SECRET' | 'SEGMENT' | 'GIT_PIPELINE' | 'GIT_ARTIFACT' | 'TYPE' | 'ENVIRONMENT_TYPE' | 'MESSAGE_PROJECT' | 'MESSAGE_TEMPLATE' | 'NOTIFICATION_TYPE'

/** One ENUM choice, or a literal extra prepended to a REFERENCE picker. */
export interface SettingOption {
  value: string
  label: string | null
}

/** One editable setting of a node type — the editor renders `control` and writes the value under `name`. */
export interface SettingMeta {
  name: string
  control: SettingControl
  label: string | null
  description: string | null
  placeholder: string | null
  /** The node's own default, string-encoded; drives omit-on-default, the new-node seed, and the placeholder. */
  default: string | null
  required: boolean
  secret: boolean
  mono: boolean
  language: string | null
  reference: ReferenceSource | null
  options: SettingOption[]
  /** Sub-fields of a GROUP_LIST row. */
  fields: SettingMeta[]
  itemLabel: string | null
  group: string | null
  visibleWhenSetting: string | null
  visibleWhenEquals: string | null
}

/** One field of a typed slot's object, for the Type Browser — mirrors backend `NodeFieldDescriptor`. */
export interface NodeFieldMeta {
  name: string
  type: string
  /** The field type's own fields when it's an object (or a list of objects); null for a leaf/cycle. */
  fields?: NodeFieldMeta[] | null
}

export interface NodeInputSlotMeta {
  name: string
  kind: string
  typeLabel: string
  description: string | null
  type: string | null
  schema: unknown
  required: boolean
  /** The expected object type's fields; null when the slot isn't a specific object. */
  structure: NodeFieldMeta[] | null
}

export interface NodeOutputSlotMeta {
  name: string
  kind: string
  error: boolean
  type: string | null
  typeLabel: string | null
  description: string | null
  /** The emitted object type's fields; null when the port isn't a specific object. */
  structure: NodeFieldMeta[] | null
}

export interface NodeType {
  key: string
  label: string
  category: string
  /** Organizational group (e.g. "WorkOps"); null = unannotated — bucketed by category instead. */
  group: string | null
  /** Second-level grouping under `group` (e.g. "Releases"); null = directly under the group. */
  subgroup: string | null
  description: string
  inputs: NodeInputSlotMeta[]
  outputs: NodeOutputSlotMeta[]
  settings: SettingMeta[]
}

/** One second-level bucket of a [NodeTypeGroup]; `name` is null for nodes directly under the group. */
export interface NodeTypeSubgroup {
  name: string | null
  types: NodeType[]
}

/** One top-level organizational bucket of the palette / node browser. */
export interface NodeTypeGroup {
  name: string
  subgroups: NodeTypeSubgroup[]
}

/** `TRANSFORM` → `Transform` — display form of a category used as a fallback group name. */
function humanizeCategory(category: string): string {
  return category.charAt(0) + category.slice(1).toLowerCase()
}

/**
 * Buckets node types by their organizational taxonomy (`group` → `subgroup`) for the editor palette
 * and the node browser. Unannotated nodes fall back to a group named after their functional category
 * (so Input/Output — deliberately ungrouped — keep their own buckets). Ordering is UX-fixed at the
 * edges and data-driven in between: "Input" first, "Core" second, "Output" last, every other group
 * alphabetical; within a group the direct (null) subgroup precedes named ones, and nodes sort by label.
 */
export function groupNodeTypes(types: NodeType[]): NodeTypeGroup[] {
  const byGroup = new Map<string, Map<string | null, NodeType[]>>()
  for (const t of types) {
    const group = t.group ?? humanizeCategory(t.category)
    const subgroups = byGroup.get(group) ?? new Map<string | null, NodeType[]>()
    const list = subgroups.get(t.subgroup) ?? []
    list.push(t)
    subgroups.set(t.subgroup, list)
    byGroup.set(group, subgroups)
  }
  const rank = (name: string) => name === 'Input' ? 0 : name === 'Core' ? 1 : name === 'Output' ? 3 : 2
  return [...byGroup.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
    .map(([name, subgroups]) => ({
      name,
      subgroups: [...subgroups.entries()]
        .sort(([a], [b]) => (a === null ? -1 : b === null ? 1 : a.localeCompare(b)))
        .map(([subgroup, list]) => ({
          name: subgroup,
          types: [...list].sort((a, b) => a.label.localeCompare(b.label)),
        })),
    }))
}

/**
 * The GraphQL selection for one `pipelines.nodeTypes` entry, shared by the editor and the node
 * browser so the two can't drift. `structureDepth` bounds the nested object expansion (GraphQL
 * can't fetch open-ended recursion; the backend caps its own walk independently).
 */
export function nodeTypeSelection(structureDepth: number): string {
  const structure = (depth: number): string =>
    depth <= 0 ? 'name type' : `name type fields { ${structure(depth - 1)} }`
  const structureFields = `structure { ${structure(structureDepth)} }`
  return `
    key label category group subgroup description
    inputs { name kind typeLabel description type schema required ${structureFields} }
    outputs { name kind error type typeLabel description ${structureFields} }
    settings {
      name control label description placeholder default required secret mono language reference
      options { value label }
      itemLabel group visibleWhenSetting visibleWhenEquals
      fields { name control label description placeholder default required secret mono language reference options { value label } }
    }
  `
}

/** One introspectable object type for the Type Browser — its serial [name], short display name, and [fields]. */
export interface BrowsableType {
  name: string
  shortName: string
  fields: NodeFieldMeta[]
}

/** The short form of a type name — strips a `shape:`/`email:` prefix and the package, e.g. `…project.ProjectRepository` → `ProjectRepository`, `shape:ReleaseBundle[]` → `ReleaseBundle[]`. */
export function shortTypeName(name: string): string {
  const base = name.startsWith('shape:')
    ? name.slice('shape:'.length)
    : name.startsWith('email:') ? name.slice('email:'.length) : name
  return base.split('.').pop() || base
}

/**
 * Converts the JSON-Schema subset (`type`/`properties`/`required`/`items`/`enum`) into browsable
 * type fields, so a schema-defined type (an email template's payload contract) introspects in the
 * Type Browser exactly like a serializer-backed one. Labels mirror the backend `NodeFieldDescriptor`
 * (`String`, `Int`, `List<…>`, enum values joined); an optional field (absent from `required`) is
 * marked with a trailing `?`.
 */
export function schemaFields(schema: unknown, depth = 0): NodeFieldMeta[] {
  if (depth > 4 || schema == null || typeof schema !== 'object' || Array.isArray(schema)) return []
  const s = schema as Record<string, unknown>
  const properties = s.properties != null && typeof s.properties === 'object' ? s.properties as Record<string, unknown> : null
  if (!properties) return []
  const required = Array.isArray(s.required) ? s.required as unknown[] : []
  const label = (t: unknown): string => {
    switch (t) {
      case 'string': return 'String'
      case 'integer': return 'Int'
      case 'number': return 'Double'
      case 'boolean': return 'Boolean'
      case 'object': return 'Object'
      default: return 'Any'
    }
  }
  return Object.entries(properties).map(([name, raw]) => {
    const p = raw != null && typeof raw === 'object' ? raw as Record<string, unknown> : {}
    const isArray = p.type === 'array'
    // For a list, the element schema drives both the label and the nested fields.
    const item = isArray && p.items != null && typeof p.items === 'object' ? p.items as Record<string, unknown> : p
    const base = Array.isArray(item.enum)
      ? (item.enum as unknown[]).map(v => String(v)).join(' | ')
      : label(item.type)
    const type = (isArray ? `List<${base}>` : base) + (required.includes(name) ? '' : '?')
    const nested = schemaFields(item, depth + 1)
    return { name, type, fields: nested.length > 0 ? nested : null }
  })
}

/**
 * Collects the distinct object types (those carrying a field structure) across every node type's input
 * and output slots — the catalog the Type Browser lets the user introspect. Deduped by serial name and
 * sorted by short name. Domain-agnostic: it only reads what the backend projected onto the slots.
 */
export function collectBrowsableTypes(nodeTypes: NodeType[]): BrowsableType[] {
  const byName = new Map<string, BrowsableType>()
  const add = (type: string | null, structure: NodeFieldMeta[] | null) => {
    if (!type || !structure?.length || byName.has(type)) return
    byName.set(type, { name: type, shortName: shortTypeName(type), fields: structure })
  }
  for (const t of nodeTypes) {
    for (const s of t.inputs) add(s.type, s.structure)
    for (const o of t.outputs) add(o.type, o.structure)
  }
  return [...byName.values()].sort((a, b) => a.shortName.localeCompare(b.shortName))
}

/**
 * Merges the complete backend type catalog with the structured types the editor can introspect.
 * Catalog-only interfaces/base types remain selectable by Cast even though they have no serializer
 * structure of their own; a structured entry wins when both sources contain the same name.
 */
export function collectCastTypes(cataloguedTypeNames: string[], structuredTypes: BrowsableType[]): BrowsableType[] {
  const byName = new Map(structuredTypes.map(t => [t.name, t]))
  for (const rawName of cataloguedTypeNames) {
    const name = rawName.trim()
    if (name && !byName.has(name)) {
      byName.set(name, { name, shortName: shortTypeName(name), fields: [] })
    }
  }
  return [...byName.values()].sort((a, b) => a.shortName.localeCompare(b.shortName) || a.name.localeCompare(b.name))
}

/**
 * Projects an Input node's instance-level output contract, matching backend `InputNode` semantics.
 * A concrete/event type is an OBJECT of that exact type, SHAPE is an anonymous OBJECT, and JSON or
 * an unconfigured input remains undeclared so static slot metadata can provide the fallback.
 */
export function inputNodeDeclaredOutput(acceptedType: unknown): { kind: string | null, type: string | null } {
  const type = typeof acceptedType === 'string' ? acceptedType.trim() : ''
  if (!type || type === 'JSON') return { kind: null, type: null }
  if (type === 'SHAPE') return { kind: 'OBJECT', type: null }
  return { kind: 'OBJECT', type }
}

/** A `{ value, label }` picker option, as the editor's reference lists are shaped. */
export interface PickerOption {
  value: string
  label: string
}

/** One field of an author-declared object shape (a pipeline's Input/Output contract). */
export interface ShapeField {
  name: string
  type: string
}

/** A pickable pipeline with its declared input/output contract (the PIPELINE reference's option shape). */
export interface PipelineOption extends PickerOption {
  description: string
  acceptedInputType: string
  inputSchema: Record<string, unknown> | null
  outputType: string | null
  hasOutput: boolean
  /** Typed fields when the input/output is a declared object shape (acceptedType/outputType = "SHAPE"). */
  inputFields: ShapeField[]
  outputFields: ShapeField[]
}

/**
 * The server-loaded option lists, keyed by REFERENCE source — passed to the generic field renderer.
 * PIPELINE carries the richer [PipelineOption] so a Run Pipeline reference can show the target's contract.
 */
export interface ReferenceContext {
  JOB?: PickerOption[]
  SCRIPT?: PickerOption[]
  EVENT?: PickerOption[]
  ATTRIBUTE_TYPE?: PickerOption[]
  SEARCH_INDEX?: PickerOption[]
  SECRET?: PickerOption[]
  /** Audience segments available to a segmentation fan-out node. */
  SEGMENT?: PickerOption[]
  PIPELINE?: PipelineOption[]
  /** All git-ci pipelines (global). */
  GIT_PIPELINE?: PickerOption[]
  /** The declared artifacts of the node's chosen git-ci pipeline — resolved per-node, not global. */
  GIT_ARTIFACT?: PickerOption[]
  /** The object types that flow between nodes — catalogued types + reusable named shapes (for a node to declare its output type). */
  TYPE?: PickerOption[]
  /** The global environment-type catalog — a relay's Get Environment names a type (resolved per-run within the release's program). */
  ENVIRONMENT_TYPE?: PickerOption[]
  /** The message projects the BML Message Server hosts (global). */
  MESSAGE_PROJECT?: PickerOption[]
  /** The templates of the node's chosen message project — resolved per-node, not global. */
  MESSAGE_TEMPLATE?: PickerOption[]
  /** The notification-type catalog (a Send Message Template's preference-gate key). */
  NOTIFICATION_TYPE?: PickerOption[]
}

/** One hosted message template: its key plus the payload contract derived from its declared serializer. */
export interface MessageTemplateInfo {
  key: string
  /** Whether the template declares an email output channel. */
  supportsEmail: boolean
  /** The serializer-derived payload skeleton (type-typical placeholder values); null = no payload. */
  samplePayload: unknown
  /** The serializer projected as a JSON-Schema subset (type/properties/required/items/enum); null = no payload. */
  payloadSchema: Record<string, unknown> | null
}

/** One hosted message project — feeds the MESSAGE_PROJECT picker and, per node, the MESSAGE_TEMPLATE picker. */
export interface MessageProjectInfo {
  project: string
  templates: MessageTemplateInfo[]
}
