import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  getFormSchemaByKey,
  getFormSchemaById,
  getAllFormSchemas,
  saveFormSchema,
  deleteFormSchema,
  submitForm,
  setFormSchemaPublished,
  addFormSchemaPermission,
  deleteFormSchemaPermission,
  GraphQLError,
  NetworkError,
} from './graphql'
import type { FormSchema, FormSchemaInput, SubmittedForm } from './types'

const API_URL = 'https://api.test.com'
const TOKEN = 'test-token'

const mockSchema: FormSchema = {
  id: '123',
  type: 'SUBMISSION',
  key: 'contact-form',
  name: 'Contact Form',
  description: 'A contact form',
  schema: { type: 'object', properties: { name: { type: 'string' } } },
  uiSchema: { version: 1, layout: [] },
  version: 1,
  public: true,
  published: true,
  permissions: [],
  created: '2025-01-01',
  modified: '2025-01-01',
}

let fetchSpy: ReturnType<typeof vi.spyOn>

function mockGraphQL(data: unknown) {
  fetchSpy.mockResolvedValueOnce(new Response(
    JSON.stringify({ data }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  ))
}

function mockGraphQLError(message: string) {
  fetchSpy.mockResolvedValueOnce(new Response(
    JSON.stringify({ errors: [{ message }] }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  ))
}

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, 'fetch')
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('setFormSchemaPublished', () => {
  it('publishes the selected schema using an authenticated mutation', async () => {
    mockGraphQL({ formSchemasMutation: { setPublished: { ...mockSchema, published: true } } })
    const result = await setFormSchemaPublished(API_URL, mockSchema.id, true, TOKEN)
    expect(result.published).toBe(true)
    const request = fetchSpy.mock.calls[0][1] as RequestInit
    expect(JSON.parse(request.body as string).variables).toEqual({ id: mockSchema.id, published: true })
    expect(request.headers).toMatchObject({ Authorization: `Bearer ${TOKEN}` })
  })
})

describe('getFormSchemaByKey', () => {
  it('returns the schema from the response', async () => {
    mockGraphQL({ formSchemas: { byKey: mockSchema } })

    const result = await getFormSchemaByKey(API_URL, 'contact-form', TOKEN)

    expect(result).toEqual(mockSchema)
  })

  it('returns null when schema not found', async () => {
    mockGraphQL({ formSchemas: { byKey: null } })

    const result = await getFormSchemaByKey(API_URL, 'missing', TOKEN)

    expect(result).toBeNull()
  })

  it('throws GraphQLError on GraphQL errors', async () => {
    mockGraphQLError('Not authorized')

    await expect(getFormSchemaByKey(API_URL, 'x', TOKEN)).rejects.toThrow(GraphQLError)
  })

  it('sends the key as a variable', async () => {
    mockGraphQL({ formSchemas: { byKey: null } })

    await getFormSchemaByKey(API_URL, 'my-key', TOKEN)

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string)
    expect(body.variables).toEqual({ key: 'my-key' })
  })

  it('works without a token', async () => {
    mockGraphQL({ formSchemas: { byKey: mockSchema } })

    const result = await getFormSchemaByKey(API_URL, 'contact-form')

    expect(result).toEqual(mockSchema)
    const headers = (fetchSpy.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    expect(headers['Authorization']).toBeUndefined()
  })
})

describe('getFormSchemaById', () => {
  it('returns the schema from the response', async () => {
    mockGraphQL({ formSchemas: { byId: mockSchema } })

    const result = await getFormSchemaById(API_URL, '123', TOKEN)

    expect(result).toEqual(mockSchema)
  })

  it('sends the id as a variable', async () => {
    mockGraphQL({ formSchemas: { byId: null } })

    await getFormSchemaById(API_URL, 'uuid-456')

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string)
    expect(body.variables).toEqual({ id: 'uuid-456' })
  })
})

describe('getAllFormSchemas', () => {
  it('returns array of schemas', async () => {
    mockGraphQL({ formSchemas: { all: [mockSchema] } })

    const result = await getAllFormSchemas(API_URL, TOKEN)

    expect(result).toEqual([mockSchema])
  })

  it('returns empty array when none exist', async () => {
    mockGraphQL({ formSchemas: { all: [] } })

    const result = await getAllFormSchemas(API_URL)

    expect(result).toEqual([])
  })
})

describe('saveFormSchema', () => {
  const input: FormSchemaInput = {
    key: 'contact-form',
    name: 'Contact Form',
    schema: { type: 'object', properties: {} },
    uiSchema: { version: 1, layout: [] },
  }

  it('returns the saved schema', async () => {
    mockGraphQL({ formSchemasMutation: { save: mockSchema } })

    const result = await saveFormSchema(API_URL, input, TOKEN)

    expect(result).toEqual(mockSchema)
  })

  it('sends the input as a variable', async () => {
    mockGraphQL({ formSchemasMutation: { save: mockSchema } })

    await saveFormSchema(API_URL, input, TOKEN)

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string)
    expect(body.variables).toEqual({ input })
  })
})

describe('deleteFormSchema', () => {
  it('returns true on successful deletion', async () => {
    mockGraphQL({ formSchemasMutation: { delete: true } })

    const result = await deleteFormSchema(API_URL, '123', TOKEN)

    expect(result).toBe(true)
  })

  it('returns false when deletion fails', async () => {
    mockGraphQL({ formSchemasMutation: { delete: false } })

    const result = await deleteFormSchema(API_URL, '123', TOKEN)

    expect(result).toBe(false)
  })
})

describe('submitForm', () => {
  const mockSubmission: SubmittedForm = {
    id: 'sub-1',
    type: 'SUBMISSION'
  }

  it('returns the submission result', async () => {
    mockGraphQL({ forms: { submit: mockSubmission } })

    const result = await submitForm(API_URL, {
      formSchemaId: 'schema-1',
      attributes: { name: 'Alice' },
    })

    expect(result).toEqual(mockSubmission)
  })

  it('throws GraphQLError on server errors', async () => {
    mockGraphQLError('Rate limit exceeded')

    await expect(
      submitForm(API_URL, { attributes: {} }),
    ).rejects.toThrow('Rate limit exceeded')
  })
})

describe('addFormSchemaPermission', () => {
  const mockPermission = {
    groupId: 'group-1',
    group: { id: 'group-1', name: 'Editors' },
    action: 'EDIT' as const,
  }

  it('returns the added permission', async () => {
    mockGraphQL({ formSchemasMutation: { addPermission: mockPermission } })

    const result = await addFormSchemaPermission(API_URL, 'entity-1', 'group-1', 'EDIT', TOKEN)

    expect(result).toEqual(mockPermission)
  })

  it('sends the correct permission variables', async () => {
    mockGraphQL({ formSchemasMutation: { addPermission: mockPermission } })

    await addFormSchemaPermission(API_URL, 'entity-1', 'group-1', 'EDIT', TOKEN)

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string)
    expect(body.variables).toEqual({
      permission: { entityId: 'entity-1', groupId: 'group-1', action: 'EDIT' },
    })
  })
})

describe('deleteFormSchemaPermission', () => {
  const mockPermission = {
    groupId: 'group-1',
    group: { id: 'group-1', name: 'Editors' },
    action: 'EDIT' as const,
  }

  it('returns the deleted permission', async () => {
    mockGraphQL({ formSchemasMutation: { deletePermission: mockPermission } })

    const result = await deleteFormSchemaPermission(API_URL, 'entity-1', 'group-1', 'EDIT', TOKEN)

    expect(result).toEqual(mockPermission)
  })
})

describe('error classes', () => {
  it('GraphQLError has correct name and errors property', () => {
    const err = new GraphQLError('test error', [{ message: 'detail' }])
    expect(err.name).toBe('GraphQLError')
    expect(err.message).toBe('test error')
    expect(err.errors).toEqual([{ message: 'detail' }])
    expect(err).toBeInstanceOf(Error)
  })

  it('NetworkError has correct name', () => {
    const err = new NetworkError('connection refused')
    expect(err.name).toBe('NetworkError')
    expect(err.message).toBe('connection refused')
    expect(err).toBeInstanceOf(Error)
  })
})
