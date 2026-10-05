import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import type { JsonSchema } from './types'

/** Validation errors keyed by property path; the empty key holds form-level errors. */
export type ValidationErrors = Record<string, string>

let _ajv: Ajv | null = null
let _prevSchemaId: string | null = null

function encodeSchemaKey(value: string): string {
  // Escape tildes for the URI resolver's URN grammar.
  return encodeURIComponent(value).replace(/~/g, '%7E')
}

function getAjv(): Ajv {
  if (!_ajv) {
    _ajv = new Ajv({ allErrors: true, verbose: true })
    addFormats(_ajv)
  }
  return _ajv
}

/** Dedicated AJV instance for single-field validation to avoid unbounded cache growth. */
let _fieldAjv: Ajv | null = null
let _prevFieldSchemaId: string | null = null

function getFieldAjv(): Ajv {
  if (!_fieldAjv) {
    _fieldAjv = new Ajv({ allErrors: true, verbose: true })
    addFormats(_fieldAjv)
  }
  return _fieldAjv
}

/**
 * Strips the $schema keyword so AJV 8 (draft-07) doesn't try to
 * resolve an unsupported meta-schema (e.g. 2020-12).
 */
function stripMetaSchema(schema: JsonSchema): JsonSchema {
  if (!schema.$schema) return schema
  const { $schema: _, ...rest } = schema
  return rest as JsonSchema
}

/**
 * Validates form data against a JSON Schema. Returns a map of
 * property paths to error messages, or an empty object if valid.
 */
export function validateFormData(
  schema: JsonSchema,
  data: Record<string, unknown>,
): ValidationErrors {
  const ajv = getAjv()
  const cleaned = stripMetaSchema(schema)
  const schemaId = cleaned.$id || `urn:bosca:form:${encodeSchemaKey(JSON.stringify(cleaned))}`
  // Evict previous anonymous schema to prevent unbounded cache growth
  if (!cleaned.$id && _prevSchemaId && _prevSchemaId !== schemaId) {
    ajv.removeSchema(_prevSchemaId)
  }
  if (!cleaned.$id) _prevSchemaId = schemaId
  let validate = ajv.getSchema(schemaId)
  if (!validate) {
    try {
      const keyed = cleaned.$id ? cleaned : { ...cleaned, $id: schemaId }
      validate = ajv.compile(keyed)
    } catch (e) {
      console.error('Failed to compile JSON Schema:', e)
      return { '': 'Form validation is unavailable due to a schema error. Please contact support.' }
    }
  }
  const valid = validate(data)

  if (valid) return {}

  const errors: ValidationErrors = {}
  if (!validate.errors?.length) return { '': 'Invalid value' }
  for (const error of validate.errors) {
    const parentPath = error.instancePath
      ? error.instancePath.substring(1).split('/').map(segment => segment.replace(/~1/g, '/').replace(/~0/g, '~')).join('.')
      : ''
    const missingProperty = error.params?.missingProperty as string | undefined
    const path = missingProperty ? (parentPath ? `${parentPath}.${missingProperty}` : missingProperty) : parentPath

    if (!errors[path]) {
      errors[path] = error.message ?? 'Invalid value'
    }
  }
  return errors
}

/**
 * Resolves a dot-path property (e.g. "address.city") to its schema definition
 * and the `required` array of the parent that contains it.
 */
function resolvePropertySchema(
  schema: JsonSchema,
  property: string,
): { propSchema: JsonSchema; isRequired: boolean } | null {
  let current: JsonSchema = schema
  let isRequired = false
  for (const segment of property.split('.')) {
    isRequired = current.required?.includes(segment) ?? false
    const propSchema = current.properties?.[segment]
    if (!propSchema) return null
    current = propSchema
  }
  return { propSchema: current, isRequired }
}

/**
 * Validates a single field value against its property schema.
 * Supports dot-path properties (e.g. "address.city") by traversing
 * the schema tree.
 * Returns the error message or null if valid.
 */
export function validateField(
  schema: JsonSchema,
  property: string,
  value: unknown,
): string | null {
  const resolved = resolvePropertySchema(schema, property)
  if (!resolved) {
    console.warn(`Property "${property}" not found in schema`)
    return null
  }

  const { propSchema, isRequired } = resolved
  if (value === undefined || value === null || value === '') {
    return isRequired ? 'This field is required' : null
  }

  const ajv = getFieldAjv()
  const fieldSchemaId = `urn:bosca:field:${encodeSchemaKey(property)}:${encodeSchemaKey(JSON.stringify(propSchema))}`
  // Evict previous field schema to prevent unbounded cache growth
  if (_prevFieldSchemaId && _prevFieldSchemaId !== fieldSchemaId) {
    ajv.removeSchema(_prevFieldSchemaId)
  }
  _prevFieldSchemaId = fieldSchemaId
  let validate = ajv.getSchema(fieldSchemaId)
  if (!validate) {
    try {
      validate = ajv.compile({ ...propSchema, $id: fieldSchemaId })
    } catch (e) {
      console.error(`Failed to compile schema for "${property}":`, e)
      return 'Schema error: unable to validate this field'
    }
  }
  const valid = validate(value)

  if (valid) return null
  return validate.errors?.[0]?.message ?? 'Invalid value'
}
