import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { BASE_URL, graphqlQuery, login, authHeaders, defaultHeaders, defaultThresholds } from './config.js';

const isSmoke = __ENV.SMOKE === 'true';

const TEST_USER = __ENV.TEST_USER || 'admin';
const TEST_PASS = __ENV.TEST_PASS || 'password';

export const options = isSmoke
  ? { vus: 1, duration: '10s', thresholds: { http_req_failed: ['rate<0.10'] } }
  : {
      scenarios: {
        graphql_login: {
          executor: 'ramping-vus',
          startVUs: 1,
          stages: [
            { duration: '30s', target: 25 },
            { duration: '1m', target: 50 },
            { duration: '30s', target: 0 },
          ],
          exec: 'graphqlLogin',
          tags: { scenario: 'graphql_login' },
        },
        rest_login: {
          executor: 'ramping-vus',
          startVUs: 1,
          startTime: '2m30s',
          stages: [
            { duration: '30s', target: 25 },
            { duration: '1m', target: 50 },
            { duration: '30s', target: 0 },
          ],
          exec: 'restLogin',
          tags: { scenario: 'rest_login' },
        },
        authenticated_cycle: {
          executor: 'constant-vus',
          vus: 50,
          duration: '2m',
          startTime: '5m',
          exec: 'authenticatedCycle',
          tags: { scenario: 'authenticated_cycle' },
        },
        token_reuse: {
          executor: 'constant-vus',
          vus: 50,
          duration: '1m',
          startTime: '7m30s',
          exec: 'tokenReuse',
          tags: { scenario: 'token_reuse' },
        },
      },
      thresholds: Object.assign({}, defaultThresholds, {
        'http_req_duration{scenario:graphql_login}': ['p(95)<500'],
        'http_req_duration{scenario:rest_login}': ['p(95)<500'],
        'http_req_duration{scenario:authenticated_cycle}': ['p(95)<300'],
        'http_req_duration{scenario:token_reuse}': ['p(95)<200'],
      }),
    };

export function setup() {
  const token = login(TEST_USER, TEST_PASS);
  return { token };
}

export function graphqlLogin() {
  const res = graphqlQuery(
    `mutation Login($identifier: String!, $password: String!) {
      security {
        login {
          password(identifier: $identifier, password: $password) {
            token { token }
            principal { id verified }
          }
        }
      }
    }`,
    { identifier: TEST_USER, password: TEST_PASS }
  );
  check(res, {
    'graphql login 200': (r) => r.status === 200,
    'graphql login has token': (r) => {
      try {
        return !!JSON.parse(r.body).data.security.login.password.token.token;
      } catch (_e) {
        return false;
      }
    },
  });
  sleep(0.5);
}

export function restLogin() {
  const payload = JSON.stringify({
    identifier: TEST_USER,
    password: TEST_PASS,
  });
  const res = http.post(`${BASE_URL}/api/v1/security/login`, payload, {
    headers: defaultHeaders,
  });
  check(res, {
    'rest login 200': (r) => r.status === 200,
  });
  sleep(0.5);
}

export function authenticatedCycle() {
  // Login
  const token = login(TEST_USER, TEST_PASS);
  check(token, {
    'cycle login succeeded': (t) => !!t,
  });
  if (!token) {
    sleep(1);
    return;
  }

  // Use token for authenticated query
  const auth = authHeaders(token);
  const res = graphqlQuery(
    '{ security { principals { current { id verified groups { name } profiles { id name } } } } }',
    {},
    auth
  );
  check(res, {
    'cycle principal 200': (r) => r.status === 200,
    'cycle principal has id': (r) => {
      try {
        return !!JSON.parse(r.body).data.security.principals.current.id;
      } catch (_e) {
        return false;
      }
    },
  });
  sleep(0.2);
}

export function tokenReuse(data) {
  if (!data.token) {
    sleep(1);
    return;
  }
  const auth = authHeaders(data.token);

  // Rapid authenticated queries with a single token
  for (let i = 0; i < 10; i++) {
    const res = graphqlQuery('{ security { principals { current { id } } } }', {}, auth);
    check(res, {
      'reuse query 200': (r) => r.status === 200,
    });
  }
  sleep(0.5);
}

export default function (data) {
  graphqlLogin();
  restLogin();
  authenticatedCycle();
  tokenReuse(data);
}
