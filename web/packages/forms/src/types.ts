/** Categorizes a form schema by its intended usage pattern. */
export type FormSchemaType = 'SUBMISSION' | 'INTERNAL' | 'WORK_OPS'

/** Valid permission actions matching the backend PermissionAction enum. */
export type PermissionActionType = 'VIEW' | 'EDIT' | 'DELETE' | 'MANAGE' | 'LIST' | 'EXECUTE' | 'IMPERSONATE'

/** A permission grant on a form schema. */
export interface FormSchemaPermission {
  groupId: string
  group: { id: string; name: string }
  action: PermissionActionType
}

/**
 * A form schema definition stored in the backend, pairing a standard
 * JSON Schema with a Bosca UI Schema for cross-platform rendering.
 */
export interface FormSchema {
  id: string
  type: FormSchemaType
  key: string
  name: string
  description: string
  schema: JsonSchema
  uiSchema: UiSchema
  configuration?: Record<string, unknown> | null
  profileMapping?: FormSchemaProfileMapping | null
  version: number
  public: boolean
  published: boolean
  permissions: FormSchemaPermission[]
  created: string
  modified: string
}

/** Input for creating or updating a form schema (upserts by key). */
export interface FormSchemaInput {
  type?: FormSchemaType
  key: string
  name: string
  description?: string
  schema: JsonSchema
  uiSchema: UiSchema
  configuration?: Record<string, unknown> | null
  profileMapping?: FormSchemaProfileMappingInput | null
  public?: boolean
}

/** Standard JSON Schema (draft 2020-12) with the properties we use. */
export interface JsonSchema {
  $schema?: string
  $id?: string
  type?: string | string[]
  properties?: Record<string, JsonSchemaProperty>
  required?: string[]
  [key: string]: unknown
}

/** A single property definition within a JSON Schema. */
export interface JsonSchemaProperty {
  type?: string | string[]
  enum?: unknown[]
  format?: string
  minimum?: number
  maximum?: number
  minLength?: number
  maxLength?: number
  pattern?: string
  description?: string
  default?: unknown
  items?: JsonSchemaProperty
  [key: string]: unknown
}

/** Bosca UI Schema defining layout tree and control configuration. */
export interface UiSchema {
  version: number
  layout: UiSchemaNode[]
}

/** Base node in the UI Schema layout tree. */
export type UiSchemaNode = SectionNode | RowNode | TabsNode | FieldNode | DisplayNode

/** Section container with heading and children. */
export interface SectionNode {
  type: 'section'
  label?: string
  children: UiSchemaNode[]
}

/** Row container distributing children across a 12-column grid. */
export interface RowNode {
  type: 'row'
  children: UiSchemaNode[]
}

/** Tabbed container where each child is a tab. */
export interface TabsNode {
  type: 'tabs'
  children: TabNode[]
}

/** Individual tab within a tabs container. */
export interface TabNode {
  type: 'tab'
  label: string
  children: UiSchemaNode[]
}

/** Field node that binds to a JSON Schema property and renders a control. */
export interface FieldNode {
  type: 'field'
  /** Dot-path to the JSON Schema property (e.g. "name", "address.city") */
  property: string
  /** Control type name from the registry (e.g. "text-input", "select") */
  control: string
  /** Grid column span (1-12) when inside a row */
  col?: number
  /** Label override (defaults to property name) */
  label?: string
  /** Placeholder text */
  placeholder?: string
  /** Number of textarea rows */
  rows?: number
  /** File accept pattern for uploads */
  accept?: string
  /** Additional control-specific configuration */
  [key: string]: unknown
}

/** Display node for non-data visual elements. */
export interface DisplayNode {
  type: 'display'
  /** Display control type (alert, divider, heading, help-text, spacer) */
  control: string
  /** Text content */
  text?: string
  /** Alert variant (info, warning, error, success) */
  variant?: string
  /** Iconify icon name (e.g. "lucide:alert-circle") */
  icon?: string
  /** Heading level (1-6) */
  level?: number
  [key: string]: unknown
}

/** Visibility levels matching the backend ProfileVisibility GraphQL enum. */
export type ProfileVisibility = 'USER' | 'FRIENDS' | 'FRIENDS_OF_FRIENDS' | 'PUBLIC' | 'SYSTEM'

/** Matches the backend ProfileAttributeInput GraphQL input type. */
export interface ProfileAttributeInput {
  id?: string
  typeId: string
  visibility: ProfileVisibility
  confidence: number
  priority: number
  source: string
  attributes?: Record<string, unknown>
  metadataId?: string
  metadataSupplementary?: string
  expiration?: string
}

/** Matches the backend ProfileInput GraphQL input type. */
export interface ProfileInput {
  name: string
  slug?: string
  attributes: ProfileAttributeInput[]
  visibility: ProfileVisibility
}

/** Matches the backend FormSchemaProfileMappingAttribute GraphQL type. */
export interface FormSchemaProfileMappingAttribute {
  typeId: string
  field: string
  attributeKey: string
}

/** Matches the backend FormSchemaProfileMapping GraphQL type. */
export interface FormSchemaProfileMapping {
  nameField: string
  visibility: ProfileVisibility
  attributes: FormSchemaProfileMappingAttribute[]
}

/** Matches the backend FormSchemaProfileMappingAttributeInput GraphQL input type. */
export interface FormSchemaProfileMappingAttributeInput {
  typeId: string
  field: string
  attributeKey: string
}

/** Matches the backend FormSchemaProfileMappingInput GraphQL input type. */
export interface FormSchemaProfileMappingInput {
  nameField: string
  visibility: ProfileVisibility
  attributes: FormSchemaProfileMappingAttributeInput[]
}

/** Matches the backend FormSubmissionInput GraphQL input type. */
export interface FormSubmissionInput {
  formSchemaId?: string
  formSchemaKey?: string
  attributes: Record<string, unknown>
  profile?: ProfileInput
}

/** A submitted form entry. */
export interface SubmittedForm {
  id: string
  type: FormSchemaType
}
