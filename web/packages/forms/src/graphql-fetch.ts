/**
 * Options for executing a GraphQL query via fetch.
 * Designed for use by form controls that need to load
 * dynamic data from the Bosca GraphQL API.
 */
export interface GraphqlFetchOptions {
  /** Base URL of the Bosca API (e.g. "https://api.example.com") */
  apiUrl: string
  /** The GraphQL query or mutation string */
  query: string
  /** Variables to pass with the query */
  variables?: Record<string, unknown>
  /** Bearer token for authentication, or null/undefined for anonymous access */
  token?: string | null
}

/**
 * Result of a GraphQL fetch operation. Exactly one of `data` or `error`
 * will be non-null, providing a simple pattern for inline error display
 * without try/catch.
 */
export interface GraphqlFetchResult<T = unknown> {
  /** The response data on success, null on failure */
  data: T | null
  /** A human-readable error message on failure, null on success */
  error: string | null
}

/**
 * Executes a GraphQL query using native fetch and returns a result tuple.
 * Unlike the internal `graphqlRequest` in graphql.ts, this function never
 * throws — errors are returned in the result object, making it suitable
 * for use in Vue component templates that display errors inline.
 *
 * @param options - The query configuration
 * @returns A result containing either `data` or `error`
 */
export async function graphqlFetch<T = unknown>(
  options: GraphqlFetchOptions,
): Promise<GraphqlFetchResult<T>> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
  }
  if (options.token) {
    headers['Authorization'] = `Bearer ${options.token}`
  }

  let response: Response
  try {
    response = await fetch(`${options.apiUrl}/graphql`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        query: options.query,
        variables: options.variables ?? {},
      }),
    })
  } catch (err) {
    return {
      data: null,
      error: `Network error: ${err instanceof Error ? err.message : String(err)}`,
    }
  }

  if (!response.ok) {
    return {
      data: null,
      error: `Request failed with status ${response.status}`,
    }
  }

  let json: { data?: T; errors?: Array<{ message?: string }> }
  try {
    json = await response.json()
  } catch {
    return { data: null, error: 'Invalid JSON response' }
  }

  if (json.errors && json.errors.length > 0) {
    return {
      data: null,
      error: json.errors[0]?.message ?? 'GraphQL request failed',
    }
  }

  if (!json.data) {
    return { data: null, error: 'Response contained no data' }
  }

  return { data: json.data, error: null }
}
