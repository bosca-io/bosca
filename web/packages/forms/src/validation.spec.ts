import { describe, it, expect, vi } from 'vitest'
import Ajv, { type ValidateFunction } from 'ajv'
import { validateFormData, validateField } from './validation'
import type { JsonSchema } from './types'

it('validates anonymous schemas with URI-sensitive property names and safely replaces cached schemas', () => {
  const first: JsonSchema = {
    type: 'object',
    properties: { 'label:key': { type: 'string', minLength: 3, description: 'A label with spaces, : and %' } },
  }
  const second: JsonSchema = { type: 'object', properties: { 'label:key': { type: 'number', minimum: 10 } } }
  expect(validateFormData(first, { 'label:key': 'valid' })).toEqual({})
  expect(validateFormData(first, { 'label:key': 'no' })).toHaveProperty('label:key')
  expect(validateField(first, 'label:key', 'valid')).toBeNull()
  expect(validateField(first, 'label:key', 'no')).toBeTruthy()
  expect(validateFormData(second, { 'label:key': 20 })).toEqual({})
  expect(validateField(second, 'label:key', 20)).toBeNull()
  expect(validateFormData({ ...first, $id: '' }, { 'label:key': 'no' })).toHaveProperty('label:key')
  expect(validateField(first, 'label:key', 'valid')).toBeNull()
})

describe('validateFormData', () => {
  it('places nested required errors on the missing field and decodes JSON Pointer property names', () => {
    const nested: JsonSchema = {
      type: 'object', properties: { address: { type: 'object', properties: { city: { type: 'string' } }, required: ['city'] } },
    }
    expect(validateFormData(nested, { address: {} })).toEqual({ 'address.city': expect.any(String) })
    const escaped: JsonSchema = { type: 'object', properties: { 'phone/label~': { type: 'string', minLength: 3 } } }
    expect(validateFormData(escaped, { 'phone/label~': 'x' })).toHaveProperty('phone/label~')
    expect(validateField(escaped, 'phone/label~', 'x')).toBeTruthy()
    expect(validateField(escaped, 'phone/label~', 'valid')).toBeNull()
  })

  it('reports object-level validation failures instead of allowing invalid submissions', () => {
    expect(validateFormData({ type: 'object', minProperties: 2 }, { name: 'Ada' })).toHaveProperty('')
  })

  it('retains failures when the validator omits diagnostic fields', () => {
    const validate = Object.assign(() => false, { errors: null }) as unknown as ValidateFunction
    const spy = vi.spyOn(Ajv.prototype, 'getSchema').mockReturnValue(validate)
    try {
      expect(validateFormData({ type: 'object' }, {})).toEqual({ '': 'Invalid value' })
      validate.errors = [{ keyword: 'custom', instancePath: '', schemaPath: '', params: {} }]
      expect(validateFormData({ type: 'object' }, {})).toEqual({ '': 'Invalid value' })
      expect(validateField({ properties: { name: { type: 'string' } } }, 'name', 'Ada')).toBe('Invalid value')
    } finally {
      spy.mockRestore()
    }
  })

  const schema: JsonSchema = {
    type: 'object',
    $id: 'test-form-data',
    properties: {
      name: { type: 'string', minLength: 1 },
      email: { type: 'string', format: 'email' },
      age: { type: 'integer', minimum: 0, maximum: 150 },
      role: { type: 'string', enum: ['admin', 'user', 'guest'] },
    },
    required: ['name', 'email'],
  }

  it('returns empty object for valid data', () => {
    const errors = validateFormData(schema, { name: 'Alice', email: 'alice@example.com', age: 30 })
    expect(errors).toEqual({})
  })

  it('reports missing required fields', () => {
    const errors = validateFormData(schema, {})
    expect(errors).toHaveProperty('name')
    expect(errors).toHaveProperty('email')
  })

  it('reports invalid email format', () => {
    const errors = validateFormData(schema, { name: 'Alice', email: 'not-an-email' })
    expect(errors).toHaveProperty('email')
  })

  it('reports integer constraint violations', () => {
    const errors = validateFormData(schema, { name: 'Alice', email: 'a@b.com', age: -5 })
    expect(errors).toHaveProperty('age')
  })

  it('reports enum violations', () => {
    const errors = validateFormData(schema, { name: 'Alice', email: 'a@b.com', role: 'superadmin' })
    expect(errors).toHaveProperty('role')
  })

  it('does not report errors for valid optional fields', () => {
    const errors = validateFormData(schema, { name: 'Alice', email: 'a@b.com', age: 25, role: 'user' })
    expect(errors).toEqual({})
  })

  it('handles nested property errors in instance paths', () => {
    const nestedSchema: JsonSchema = {
      type: 'object',
      $id: 'test-nested',
      properties: {
        address: {
          type: 'object',
          properties: {
            city: { type: 'string', minLength: 1 },
          },
          required: ['city'],
        },
      },
      required: ['address'],
    }
    const errors = validateFormData(nestedSchema, { address: { city: '' } })
    // AJV will report the path as "address.city"
    expect(Object.keys(errors).length).toBeGreaterThan(0)
  })

  it('returns a root-level error when schema compilation fails', () => {
    const badSchema: JsonSchema = {
      type: 'object',
      $id: 'bad-schema',
      properties: { x: { type: 'invalid-type-that-does-not-exist' as any } },
    }
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const errors = validateFormData(badSchema, { x: 'hello' })
    expect(errors['']).toBeTruthy()
    expect(Object.keys(errors).length).toBe(1)
    spy.mockRestore()
  })

  it('validates schemas with $schema draft 2020-12 without error', () => {
    const draft2020Schema: JsonSchema = {
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      $id: 'test-draft-2020-12',
      type: 'object',
      properties: {
        name: { type: 'string', minLength: 1 },
      },
      required: ['name'],
    }
    const validErrors = validateFormData(draft2020Schema, { name: 'Alice' })
    expect(validErrors).toEqual({})
    const invalidErrors = validateFormData(draft2020Schema, {})
    expect(invalidErrors).toHaveProperty('name')
  })

  it('caches compiled schemas for repeated calls', () => {
    const s: JsonSchema = {
      type: 'object',
      $id: 'cache-test-schema',
      properties: { x: { type: 'string' } },
      required: ['x'],
    }
    // First call compiles the schema
    const e1 = validateFormData(s, { x: 'ok' })
    // Second call should reuse the cached validator
    const e2 = validateFormData(s, {})
    expect(e1).toEqual({})
    expect(e2).toHaveProperty('x')
  })

  it('handles schemas without $id (anonymous schemas)', () => {
    const anonSchema: JsonSchema = {
      type: 'object',
      properties: { val: { type: 'number' } },
      required: ['val'],
    }
    const errors = validateFormData(anonSchema, {})
    expect(errors).toHaveProperty('val')
  })

  it('evicts previous anonymous schema to prevent cache growth', () => {
    const schema1: JsonSchema = {
      type: 'object',
      properties: { a: { type: 'string' } },
      required: ['a'],
    }
    const schema2: JsonSchema = {
      type: 'object',
      properties: { b: { type: 'number' } },
      required: ['b'],
    }
    // First anonymous schema
    validateFormData(schema1, {})
    // Second anonymous schema should evict the first
    const errors = validateFormData(schema2, {})
    expect(errors).toHaveProperty('b')
  })

  it('reports only the first error per property path', () => {
    const s: JsonSchema = {
      type: 'object',
      $id: 'first-error-test',
      properties: {
        email: { type: 'string', format: 'email', minLength: 5 },
      },
      required: ['email'],
    }
    const errors = validateFormData(s, { email: 'x' })
    // Should have exactly one error for email, not multiple
    expect(typeof errors['email']).toBe('string')
  })
})

describe('validateField', () => {
  const schema: JsonSchema = {
    type: 'object',
    properties: {
      name: { type: 'string', minLength: 2 },
      age: { type: 'integer', minimum: 0 },
      email: { type: 'string', format: 'email' },
      bio: { type: 'string' },
      address: {
        type: 'object',
        properties: {
          city: { type: 'string', minLength: 1 },
          zip: { type: 'string', pattern: '^\\d{5}$' },
        },
        required: ['city'],
      },
    },
    required: ['name', 'email'],
  }

  it('returns null for a valid field value', () => {
    expect(validateField(schema, 'name', 'Alice')).toBeNull()
  })

  it('returns "This field is required" for empty required field', () => {
    expect(validateField(schema, 'name', '')).toBe('This field is required')
    expect(validateField(schema, 'name', null)).toBe('This field is required')
    expect(validateField(schema, 'name', undefined)).toBe('This field is required')
  })

  it('returns null for empty optional field', () => {
    expect(validateField(schema, 'bio', '')).toBeNull()
    expect(validateField(schema, 'bio', null)).toBeNull()
    expect(validateField(schema, 'bio', undefined)).toBeNull()
  })

  it('returns error message for constraint violations', () => {
    const error = validateField(schema, 'name', 'A')
    expect(error).toBeTruthy()
    expect(typeof error).toBe('string')
  })

  it('validates format constraints', () => {
    const error = validateField(schema, 'email', 'not-an-email')
    expect(error).toBeTruthy()
  })

  it('returns null for valid format', () => {
    expect(validateField(schema, 'email', 'test@example.com')).toBeNull()
  })

  it('validates integer constraints', () => {
    const error = validateField(schema, 'age', -1)
    expect(error).toBeTruthy()
  })

  it('resolves dot-path properties (nested schema)', () => {
    expect(validateField(schema, 'address.city', 'Portland')).toBeNull()
  })

  it('returns required error for empty nested required field', () => {
    expect(validateField(schema, 'address.city', '')).toBe('This field is required')
  })

  it('validates nested field constraints', () => {
    const error = validateField(schema, 'address.zip', 'abc')
    expect(error).toBeTruthy()
  })

  it('returns null for valid nested field', () => {
    expect(validateField(schema, 'address.zip', '97201')).toBeNull()
  })

  it('returns null and warns for nonexistent property', () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(validateField(schema, 'nonexistent', 'val')).toBeNull()
    expect(spy).toHaveBeenCalledWith('Property "nonexistent" not found in schema')
    spy.mockRestore()
  })

  it('returns null and warns for nonexistent nested property', () => {
    const spy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(validateField(schema, 'address.state', 'OR')).toBeNull()
    spy.mockRestore()
  })

  it('returns schema error for uncompilable property schema', () => {
    const badSchema: JsonSchema = {
      type: 'object',
      properties: {
        broken: { type: 'not-a-real-type' as any },
      },
    }
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const error = validateField(badSchema, 'broken', 'value')
    expect(error).toContain('Schema error')
    spy.mockRestore()
  })

  it('caches field validators across calls', () => {
    // Call twice with same property — second should hit cache
    expect(validateField(schema, 'name', 'Alice')).toBeNull()
    expect(validateField(schema, 'name', 'A')).toBeTruthy()
  })
})
