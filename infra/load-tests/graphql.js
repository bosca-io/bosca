import { check, sleep, group } from 'k6';
import { graphqlQuery, login, authHeaders, defaultThresholds } from './config.js';

const isSmoke = __ENV.SMOKE === 'true';

const TEST_USER = __ENV.TEST_USER || 'admin';
const TEST_PASS = __ENV.TEST_PASS || 'password';

export const options = isSmoke
  ? { vus: 1, duration: '10s', thresholds: { http_req_failed: ['rate<0.05'] } }
  : {
      scenarios: {
        read_queries: {
          executor: 'ramping-vus',
          startVUs: 1,
          stages: [
            { duration: '30s', target: 50 },
            { duration: '2m', target: 100 },
            { duration: '30s', target: 0 },
          ],
          exec: 'readQueries',
          tags: { scenario: 'read_queries' },
        },
        write_mutations: {
          executor: 'ramping-vus',
          startVUs: 1,
          startTime: '3m30s',
          stages: [
            { duration: '30s', target: 25 },
            { duration: '2m', target: 50 },
            { duration: '30s', target: 0 },
          ],
          exec: 'writeMutations',
          tags: { scenario: 'write_mutations' },
        },
      },
      thresholds: Object.assign({}, defaultThresholds, {
        'http_req_duration{scenario:read_queries}': ['p(95)<300'],
        'http_req_duration{scenario:write_mutations}': ['p(95)<500'],
      }),
    };

export function setup() {
  const token = login(TEST_USER, TEST_PASS);
  if (!token) {
    console.warn('Login failed — authenticated tests will be skipped');
  }
  return { token };
}

export function readQueries(data) {
  const auth = data.token ? authHeaders(data.token) : {};

  group('server_now', () => {
    const res = graphqlQuery('{ server { now } }');
    check(res, {
      'server now 200': (r) => r.status === 200,
      'server now has data': (r) => JSON.parse(r.body).data?.server?.now,
    });
  });

  group('languages', () => {
    const res = graphqlQuery('{ languages { all { tag name localName } } }');
    check(res, {
      'languages 200': (r) => r.status === 200,
      'languages returns list': (r) => {
        const body = JSON.parse(r.body);
        return Array.isArray(body.data?.languages?.all);
      },
    });
  });

  group('categories', () => {
    const res = graphqlQuery(
      '{ content { categories { all { id name } } } }',
      {},
      auth
    );
    check(res, {
      'categories 200': (r) => r.status === 200,
    });
  });

  group('security_actions', () => {
    const res = graphqlQuery('{ security { actions } }', {}, auth);
    check(res, {
      'actions 200': (r) => r.status === 200,
      'actions returns list': (r) => {
        const body = JSON.parse(r.body);
        return Array.isArray(body.data?.security?.actions);
      },
    });
  });

  group('content_health_check', () => {
    const res = graphqlQuery(
      '{ content { healthCheck { deletedItems { count } } } }',
      {},
      auth
    );
    check(res, {
      'health check 200': (r) => r.status === 200,
    });
  });

  if (data.token) {
    group('current_principal', () => {
      const res = graphqlQuery(
        '{ security { principals { current { id verified groups { name } } } } }',
        {},
        auth
      );
      check(res, {
        'principal 200': (r) => r.status === 200,
        'principal has id': (r) => JSON.parse(r.body).data?.security?.principals?.current?.id,
      });
    });
  }

  group('search', () => {
    const res = graphqlQuery(
      `{ search { search(query: { query: "test", limit: 10, offset: 0, storageSystemName: "Default Search" }) { estimatedHits } } }`,
      {},
      auth
    );
    check(res, {
      'search 200': (r) => r.status === 200,
    });
  });

  sleep(0.2);
}

export function writeMutations(data) {
  if (!data.token) {
    sleep(1);
    return;
  }
  const auth = authHeaders(data.token);

  group('clear_cache', () => {
    const res = graphqlQuery('mutation { clearCache }', {}, auth);
    check(res, {
      'clear cache 200': (r) => r.status === 200,
    });
  });

  group('create_collection', () => {
    const name = `k6-collection-${Date.now()}-${__VU}-${__ITER}`;
    const res = graphqlQuery(
      `mutation CreateCollection($input: CollectionInput!) {
        content { collection { add(collection: $input) { id name created } } }
      }`,
      { input: { name, collectionType: 'STANDARD' } },
      auth
    );
    check(res, {
      'create collection 200': (r) => r.status === 200,
      'collection has id': (r) => {
        const body = JSON.parse(r.body);
        return body.data?.content?.collection?.add?.id;
      },
    });
  });

  group('create_metadata', () => {
    const name = `k6-metadata-${Date.now()}-${__VU}-${__ITER}`;
    const res = graphqlQuery(
      `mutation CreateMetadata($input: MetadataInput!) {
        content { metadata { add(metadata: $input) { id name created } } }
      }`,
      { input: { name, contentType: 'text/plain', languageTag: 'en' } },
      auth
    );
    check(res, {
      'create metadata 200': (r) => r.status === 200,
      'metadata has id': (r) => {
        const body = JSON.parse(r.body);
        return body.data?.content?.metadata?.add?.id;
      },
    });
  });

  sleep(0.5);
}

export default function (data) {
  readQueries(data);
  writeMutations(data);
}
