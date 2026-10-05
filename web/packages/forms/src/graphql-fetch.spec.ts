import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { graphqlFetch } from './graphql-fetch'

describe('graphqlFetch', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    fetchSpy = vi.spyOn(globalThis, 'fetch')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  function mockResponse(body: unknown, status = 200) {
    fetchSpy.mockResolvedValueOnce(new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }))
  }

  const baseOptions = {
    apiUrl: 'https://api.test.com',
    query: '{ hello }',
    variables: { id: '1' },
  }

  it('returns data on a successful response', async () => {
    mockResponse({ data: { hello: 'world' } })

    const result = await graphqlFetch(baseOptions)

    expect(result).toEqual({ data: { hello: 'world' }, error: null })
  })

  it('sends POST to apiUrl/graphql with correct headers', async () => {
    mockResponse({ data: {} })

    await graphqlFetch(baseOptions)

    expect(fetchSpy).toHaveBeenCalledWith('https://api.test.com/graphql', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
      body: JSON.stringify({ query: '{ hello }', variables: { id: '1' } }),
    })
  })

  it('includes Authorization header when token is provided', async () => {
    mockResponse({ data: {} })

    await graphqlFetch({ ...baseOptions, token: 'my-token' })

    const callArgs = fetchSpy.mock.calls[0]
    const requestInit = callArgs[1] as RequestInit
    expect((requestInit.headers as Record<string, string>)['Authorization']).toBe('Bearer my-token')
  })

  it('omits Authorization header when token is null', async () => {
    mockResponse({ data: {} })

    await graphqlFetch({ ...baseOptions, token: null })

    const callArgs = fetchSpy.mock.calls[0]
    const requestInit = callArgs[1] as RequestInit
    expect((requestInit.headers as Record<string, string>)['Authorization']).toBeUndefined()
  })

  it('omits Authorization header when token is undefined', async () => {
    mockResponse({ data: {} })

    await graphqlFetch(baseOptions)

    const callArgs = fetchSpy.mock.calls[0]
    const requestInit = callArgs[1] as RequestInit
    expect((requestInit.headers as Record<string, string>)['Authorization']).toBeUndefined()
  })

  it('defaults variables to empty object when not provided', async () => {
    mockResponse({ data: {} })

    await graphqlFetch({ apiUrl: 'https://api.test.com', query: '{ q }' })

    const callArgs = fetchSpy.mock.calls[0]
    const body = JSON.parse((callArgs[1] as RequestInit).body as string)
    expect(body.variables).toEqual({})
  })

  it('returns network error when fetch throws', async () => {
    fetchSpy.mockRejectedValueOnce(new Error('Connection refused'))

    const result = await graphqlFetch(baseOptions)

    expect(result.data).toBeNull()
    expect(result.error).toBe('Network error: Connection refused')
  })

  it('returns network error with stringified non-Error throwable', async () => {
    fetchSpy.mockRejectedValueOnce('raw string error')

    const result = await graphqlFetch(baseOptions)

    expect(result.error).toBe('Network error: raw string error')
  })

  it('returns error for non-OK HTTP status', async () => {
    fetchSpy.mockResolvedValueOnce(new Response('Server Error', { status: 500 }))

    const result = await graphqlFetch(baseOptions)

    expect(result.data).toBeNull()
    expect(result.error).toBe('Request failed with status 500')
  })

  it('returns error for 401 unauthorized', async () => {
    fetchSpy.mockResolvedValueOnce(new Response('Unauthorized', { status: 401 }))

    const result = await graphqlFetch(baseOptions)

    expect(result.error).toBe('Request failed with status 401')
  })

  it('returns error when response body is not valid JSON', async () => {
    fetchSpy.mockResolvedValueOnce(new Response('not json', {
      status: 200,
      headers: { 'Content-Type': 'text/html' },
    }))

    const result = await graphqlFetch(baseOptions)

    expect(result.data).toBeNull()
    expect(result.error).toBe('Invalid JSON response')
  })

  it('returns first GraphQL error message from response', async () => {
    mockResponse({
      errors: [
        { message: 'Field "x" not found' },
        { message: 'Another error' },
      ],
    })

    const result = await graphqlFetch(baseOptions)

    expect(result.data).toBeNull()
    expect(result.error).toBe('Field "x" not found')
  })

  it('returns fallback message when GraphQL error has no message', async () => {
    mockResponse({ errors: [{}] })

    const result = await graphqlFetch(baseOptions)

    expect(result.error).toBe('GraphQL request failed')
  })

  it('returns error when response has no data field', async () => {
    mockResponse({})

    const result = await graphqlFetch(baseOptions)

    expect(result.data).toBeNull()
    expect(result.error).toBe('Response contained no data')
  })

  it('treats empty errors array as no errors', async () => {
    mockResponse({ data: { ok: true }, errors: [] })

    const result = await graphqlFetch(baseOptions)

    expect(result.data).toEqual({ ok: true })
    expect(result.error).toBeNull()
  })
})
