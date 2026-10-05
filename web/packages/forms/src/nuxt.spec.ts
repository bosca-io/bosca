import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createApp, type App } from 'vue'
import { setupBoscaForms, useBoscaForms } from './nuxt'

let fetchSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, 'fetch')
})

afterEach(() => {
  vi.restoreAllMocks()
})

function mockGraphQL(data: unknown) {
  fetchSpy.mockResolvedValueOnce(new Response(
    JSON.stringify({ data }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  ))
}

function createMockNuxtApp(): { nuxtApp: { vueApp: App }; app: App } {
  const app = createApp({ render: () => null })
  return { nuxtApp: { vueApp: app }, app }
}

describe('setupBoscaForms', () => {
  it('saves a schema without an access token and invalidates the cached value', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const state = setupBoscaForms(nuxtApp, { apiUrl: '/graphql' })
    mockGraphQL({ formSchemas: { byKey: null } })
    expect(await state.api.getByKey('contact')).toBeNull()
    mockGraphQL({ formSchemasMutation: { save: { id: 'new', key: 'contact' } } })
    await state.api.save({ key: 'contact', name: 'Contact', schema: {}, uiSchema: { version: 1, layout: [] } })
    expect((fetchSpy.mock.calls[0][1] as RequestInit).headers).not.toHaveProperty('Authorization')
    expect(state.schemaCache.value.has('contact')).toBe(false)
  })

  it('returns state with configured apiUrl', () => {
    const { nuxtApp } = createMockNuxtApp()
    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    expect(state.apiUrl).toBe('https://api.test.com')
  })

  it('defaults getToken to a function returning null', () => {
    const { nuxtApp } = createMockNuxtApp()
    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    expect(state.getToken()).toBeNull()
  })

  it('uses provided getToken function', () => {
    const { nuxtApp } = createMockNuxtApp()
    const state = setupBoscaForms(nuxtApp, {
      apiUrl: 'https://api.test.com',
      getToken: () => 'my-token',
    })

    expect(state.getToken()).toBe('my-token')
  })

  it('stores analytics instance when provided', () => {
    const { nuxtApp } = createMockNuxtApp()
    const analytics = { sink: { logEvent: vi.fn() } }

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com', analytics })

    expect(state.analytics).toBe(analytics)
  })

  it('defaults analytics to null when not provided', () => {
    const { nuxtApp } = createMockNuxtApp()
    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    expect(state.analytics).toBeNull()
  })

  it('initializes an empty schema cache', () => {
    const { nuxtApp } = createMockNuxtApp()
    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    expect(state.schemaCache.value.size).toBe(0)
  })
})

describe('useBoscaForms', () => {
  it('throws when called before setupBoscaForms', () => {
    const app = createApp({ render: () => null })
    app.runWithContext(() => {
      expect(() => useBoscaForms()).toThrow(
        'Bosca Forms not initialized. Ensure setupBoscaForms() has been called in a Nuxt plugin.',
      )
    })
  })

  it('returns state after setupBoscaForms has been called', () => {
    const { nuxtApp, app } = createMockNuxtApp()
    setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    app.runWithContext(() => {
      const state = useBoscaForms()
      expect(state.apiUrl).toBe('https://api.test.com')
    })
  })

  it('throws when setupBoscaForms is called twice on the same app', () => {
    const { nuxtApp } = createMockNuxtApp()
    setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    expect(() => setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })).toThrow(
      'setupBoscaForms has already been called',
    )
  })
})

describe('fetchSchema', () => {
  it('fetches schema from API and caches it', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const mockSchema = {
      id: '1', key: 'test', name: 'Test', description: '',
      schema: {}, uiSchema: { version: 1, layout: [] },
      version: 1, public: true, published: true,
      permissions: [], created: '', modified: '',
    }
    mockGraphQL({ formSchemas: { byKey: mockSchema } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })
    const result = await state.fetchSchema('test')

    expect(result).toEqual(mockSchema)
    expect(state.schemaCache.value.get('test')).toEqual(mockSchema)
  })

  it('returns cached schema on second call without re-fetching', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const mockSchema = {
      id: '1', key: 'cached', name: 'Cached', description: '',
      schema: {}, uiSchema: { version: 1, layout: [] },
      version: 1, public: true, published: true,
      permissions: [], created: '', modified: '',
    }
    mockGraphQL({ formSchemas: { byKey: mockSchema } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    const first = await state.fetchSchema('cached')
    const second = await state.fetchSchema('cached')

    expect(first).toEqual(second)
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('re-fetches schema after invalidateSchema is called', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const v1 = {
      id: '1', key: 'evolving', name: 'V1', description: '',
      schema: {}, uiSchema: { version: 1, layout: [] },
      version: 1, public: true, published: true,
      permissions: [], created: '', modified: '',
    }
    const v2 = { ...v1, name: 'V2', version: 2 }
    mockGraphQL({ formSchemas: { byKey: v1 } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    const first = await state.fetchSchema('evolving')
    expect(first?.name).toBe('V1')

    state.invalidateSchema('evolving')
    expect(state.schemaCache.value.has('evolving')).toBe(false)

    mockGraphQL({ formSchemas: { byKey: v2 } })
    const second = await state.fetchSchema('evolving')
    expect(second?.name).toBe('V2')
    expect(fetchSpy).toHaveBeenCalledTimes(2)
  })

  it('returns null when schema not found and does not cache', async () => {
    const { nuxtApp } = createMockNuxtApp()
    mockGraphQL({ formSchemas: { byKey: null } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })
    const result = await state.fetchSchema('missing')

    expect(result).toBeNull()
    expect(state.schemaCache.value.has('missing')).toBe(false)
  })
})

describe('api methods', () => {
  it('api.getByKey delegates to the GraphQL API', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const mockSchema = {
      id: '1', key: 'k', name: 'N', description: '',
      schema: {}, uiSchema: { version: 1, layout: [] },
      version: 1, public: true, published: true,
      permissions: [], created: '', modified: '',
    }
    mockGraphQL({ formSchemas: { byKey: mockSchema } })

    const state = setupBoscaForms(nuxtApp, {
      apiUrl: 'https://api.test.com',
      getToken: () => 'tok',
    })

    const result = await state.api.getByKey('k')
    expect(result).toEqual(mockSchema)

    const headers = (fetchSpy.mock.calls[0][1] as RequestInit).headers as Record<string, string>
    expect(headers['Authorization']).toBe('Bearer tok')
  })

  it('api.getById delegates correctly', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const mockSchema = {
      id: '2', key: 'k2', name: 'N2', description: '',
      schema: {}, uiSchema: { version: 1, layout: [] },
      version: 1, public: true, published: true,
      permissions: [], created: '', modified: '',
    }
    mockGraphQL({ formSchemas: { byId: mockSchema } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    const result = await state.api.getById('2')
    expect(result).toEqual(mockSchema)
  })

  it('api.getAll delegates correctly', async () => {
    const { nuxtApp } = createMockNuxtApp()
    mockGraphQL({ formSchemas: { all: [] } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    const result = await state.api.getAll()
    expect(result).toEqual([])
  })

  it('api.save delegates correctly', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const saved = {
      id: '3', key: 'k3', name: 'Saved', description: '',
      schema: {}, uiSchema: { version: 1, layout: [] },
      version: 2, public: false, published: false,
      permissions: [], created: '', modified: '',
    }
    mockGraphQL({ formSchemasMutation: { save: saved } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    const result = await state.api.save({
      key: 'k3', name: 'Saved',
      schema: {}, uiSchema: { version: 1, layout: [] },
    })
    expect(result).toEqual(saved)
  })

  it('api.save invalidates the schema cache for the saved key', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const original = {
      id: '4', key: 'k4', name: 'Original', description: '',
      schema: {}, uiSchema: { version: 1, layout: [] },
      version: 1, public: true, published: true,
      permissions: [], created: '', modified: '',
    }
    mockGraphQL({ formSchemas: { byKey: original } })
    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })
    await state.fetchSchema('k4')
    expect(state.schemaCache.value.has('k4')).toBe(true)

    const saved = { ...original, version: 2 }
    mockGraphQL({ formSchemasMutation: { save: saved } })
    await state.api.save({
      key: 'k4', name: 'Original',
      schema: {}, uiSchema: { version: 1, layout: [] },
    })
    expect(state.schemaCache.value.has('k4')).toBe(false)
  })

  it('api.delete delegates correctly', async () => {
    const { nuxtApp } = createMockNuxtApp()
    mockGraphQL({ formSchemasMutation: { delete: true } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    const result = await state.api.delete('123')
    expect(result).toBe(true)
  })

  it('api.submitForm delegates correctly', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const submission = {
      id: 'sub-1', attributes: { x: 1 }, status: 'pending',
      created: '', modified: '',
    }
    mockGraphQL({ forms: { submit: submission } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    const result = await state.api.submitForm({ attributes: { x: 1 } })
    expect(result).toEqual(submission)
  })

  it('api.submitForm sends profile data when provided', async () => {
    const { nuxtApp } = createMockNuxtApp()
    const submission = {
      id: 'sub-2', attributes: { name: 'Jane' }, status: 'pending',
      created: '', modified: '',
    }
    mockGraphQL({ forms: { submit: submission } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    await state.api.submitForm({
      formSchemaKey: 'contact',
      attributes: { name: 'Jane', message: 'Hello' },
      profile: {
        name: 'Jane',
        visibility: 'USER',
        attributes: [
          {
            typeId: 'bosca.profiles.email',
            attributes: { email: 'jane@example.com' },
            confidence: 100,
            priority: 1,
            source: 'form:contact',
            visibility: 'USER',
          },
        ],
      },
    })

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string)
    expect(body.variables.input.profile).toBeDefined()
    expect(body.variables.input.profile.name).toBe('Jane')
    expect(body.variables.input.profile.visibility).toBe('USER')
    expect(body.variables.input.profile.attributes).toHaveLength(1)
    expect(body.variables.input.profile.attributes[0].typeId).toBe('bosca.profiles.email')
    expect(body.variables.input.profile.attributes[0].attributes.email).toBe('jane@example.com')
  })

  it('api.submitForm omits profile when not provided', async () => {
    const { nuxtApp } = createMockNuxtApp()
    mockGraphQL({ forms: { submit: { id: 'sub-3', attributes: {}, status: 'ok', created: '', modified: '' } } })

    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })
    await state.api.submitForm({ attributes: { x: 1 } })

    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string)
    expect(body.variables.input.profile).toBeUndefined()
  })
})

describe('getProfile', () => {
  it('defaults getProfile to a function returning null', () => {
    const { nuxtApp } = createMockNuxtApp()
    const state = setupBoscaForms(nuxtApp, { apiUrl: 'https://api.test.com' })

    expect(state.getProfile()).toBeNull()
  })

  it('uses provided getProfile function', () => {
    const { nuxtApp } = createMockNuxtApp()
    const profile = {
      name: 'Alice',
      attributes: [{ typeId: 'bosca.profiles.email', attributes: { email: 'alice@test.com' } }],
    }
    const state = setupBoscaForms(nuxtApp, {
      apiUrl: 'https://api.test.com',
      getProfile: () => profile,
    })

    expect(state.getProfile()).toEqual(profile)
    expect(state.getProfile()!.name).toBe('Alice')
    expect(state.getProfile()!.attributes[0].typeId).toBe('bosca.profiles.email')
  })

  it('returns null when getProfile returns undefined', () => {
    const { nuxtApp } = createMockNuxtApp()
    const state = setupBoscaForms(nuxtApp, {
      apiUrl: 'https://api.test.com',
      getProfile: () => undefined,
    })

    expect(state.getProfile()).toBeUndefined()
  })
})
