import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const defaultHeaders = {
  'Content-Type': 'application/json',
  'Accept': 'application/json',
};

/**
 * Sends a GraphQL query to the server and returns the parsed response.
 *
 * @param {string} query - The GraphQL query or mutation string
 * @param {object} [variables] - Optional variables for the query
 * @param {object} [extraHeaders] - Optional additional headers (e.g., Authorization)
 * @returns {object} The k6 HTTP response
 */
export function graphqlQuery(query, variables = {}, extraHeaders = {}) {
  const payload = JSON.stringify({ query, variables });
  return http.post(`${BASE_URL}/graphql`, payload, {
    headers: Object.assign({}, defaultHeaders, extraHeaders),
  });
}

/**
 * Authenticates with the server and returns the auth token.
 * Uses the GraphQL login mutation.
 *
 * @param {string} identifier - Email or username
 * @param {string} password - Password
 * @returns {string|null} The JWT token, or null if login failed
 */
export function login(identifier, password) {
  const res = graphqlQuery(
    `mutation Login($identifier: String!, $password: String!) {
      security {
        login {
          password(identifier: $identifier, password: $password) {
            token { token }
          }
        }
      }
    }`,
    { identifier, password }
  );

  try {
    const body = JSON.parse(res.body);
    return body.data.security.login.password.token.token;
  } catch (_e) {
    return null;
  }
}

/**
 * Returns an Authorization header object for authenticated requests.
 *
 * @param {string} token - JWT token from login
 * @returns {object} Headers object with Bearer token
 */
export function authHeaders(token) {
  return { Authorization: `Bearer ${token}` };
}

/**
 * Default thresholds for load tests.
 */
export const defaultThresholds = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500'],
};

/**
 * Smoke test options: 1 VU, 10s — for GraalVM native image validation.
 */
export const smokeOptions = {
  vus: 1,
  duration: '10s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};
