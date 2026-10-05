import { check, sleep, group } from 'k6';
import { graphqlQuery, login, authHeaders, defaultThresholds } from './config.js';

const isSmoke = __ENV.SMOKE === 'true';

const TEST_USER = __ENV.TEST_USER || 'admin';
const TEST_PASS = __ENV.TEST_PASS || 'password';

export const options = isSmoke
  ? { vus: 1, duration: '10s', thresholds: { http_req_failed: ['rate<0.10'] } }
  : {
      scenarios: {
        create_collections: {
          executor: 'ramping-vus',
          startVUs: 1,
          stages: [
            { duration: '30s', target: 25 },
            { duration: '2m', target: 50 },
            { duration: '30s', target: 0 },
          ],
          exec: 'createCollections',
          tags: { scenario: 'create_collections' },
        },
        create_metadata: {
          executor: 'ramping-vus',
          startVUs: 1,
          startTime: '3m30s',
          stages: [
            { duration: '30s', target: 25 },
            { duration: '2m', target: 50 },
            { duration: '30s', target: 0 },
          ],
          exec: 'createMetadata',
          tags: { scenario: 'create_metadata' },
        },
        mixed_crud: {
          executor: 'ramping-vus',
          startVUs: 1,
          startTime: '7m',
          stages: [
            { duration: '30s', target: 50 },
            { duration: '3m', target: 100 },
            { duration: '30s', target: 0 },
          ],
          exec: 'mixedCrud',
          tags: { scenario: 'mixed_crud' },
        },
        concurrent_writes: {
          executor: 'constant-vus',
          vus: 200,
          duration: '1m',
          startTime: '11m',
          exec: 'concurrentWrites',
          tags: { scenario: 'concurrent_writes' },
        },
      },
      thresholds: Object.assign({}, defaultThresholds, {
        'http_req_duration{scenario:create_collections}': ['p(95)<500'],
        'http_req_duration{scenario:create_metadata}': ['p(95)<500'],
        'http_req_duration{scenario:mixed_crud}': ['p(95)<500'],
        'http_req_duration{scenario:concurrent_writes}': ['p(95)<1000'],
      }),
    };

export function setup() {
  const token = login(TEST_USER, TEST_PASS);
  if (!token) {
    console.error('Login failed — content operations require authentication');
  }
  return { token };
}

function createCollection(auth, suffix) {
  const name = `k6-col-${Date.now()}-${__VU}-${suffix}`;
  const res = graphqlQuery(
    `mutation CreateCollection($input: CollectionInput!) {
      content { collection { add(collection: $input) { id name } } }
    }`,
    { input: { name, collectionType: 'STANDARD', attributes: {} } },
    auth
  );
  const body = JSON.parse(res.body);
  return {
    res,
    id: body.data?.content?.collection?.add?.id || null,
  };
}

function createMetadataItem(auth, suffix, parentCollectionId) {
  const name = `k6-meta-${Date.now()}-${__VU}-${suffix}`;
  const variables = {
    input: {
      name,
      contentType: 'text/plain',
      languageTag: 'en',
      attributes: { source: 'k6-load-test' },
    },
  };
  if (parentCollectionId) {
    variables.input.parentCollectionId = parentCollectionId;
  }
  const res = graphqlQuery(
    `mutation CreateMetadata($input: MetadataInput!) {
      content { metadata { add(metadata: $input) { id name } } }
    }`,
    variables,
    auth
  );
  const body = JSON.parse(res.body);
  return {
    res,
    id: body.data?.content?.metadata?.add?.id || null,
  };
}

function addMetadataToCollection(auth, collectionId, metadataId) {
  return graphqlQuery(
    `mutation AddChild($id: UUID!, $metadataId: UUID!) {
      content { collection { addChildMetadata(id: $id, metadataId: $metadataId) { id metadataCount } } }
    }`,
    { id: collectionId, metadataId },
    auth
  );
}

export function createCollections(data) {
  if (!data.token) { sleep(1); return; }
  const auth = authHeaders(data.token);

  const result = createCollection(auth, __ITER);
  check(result.res, {
    'create collection 200': (r) => r.status === 200,
    'collection has id': () => !!result.id,
  });
  sleep(0.2);
}

export function createMetadata(data) {
  if (!data.token) { sleep(1); return; }
  const auth = authHeaders(data.token);

  const result = createMetadataItem(auth, __ITER, null);
  check(result.res, {
    'create metadata 200': (r) => r.status === 200,
    'metadata has id': () => !!result.id,
  });
  sleep(0.2);
}

export function mixedCrud(data) {
  if (!data.token) { sleep(1); return; }
  const auth = authHeaders(data.token);

  group('create_collection', () => {
    const col = createCollection(auth, `crud-${__ITER}`);
    check(col.res, {
      'crud collection created': (r) => r.status === 200,
    });

    if (col.id) {
      group('create_and_add_metadata', () => {
        const meta = createMetadataItem(auth, `crud-${__ITER}`, null);
        check(meta.res, {
          'crud metadata created': (r) => r.status === 200,
        });

        if (meta.id) {
          const addRes = addMetadataToCollection(auth, col.id, meta.id);
          check(addRes, {
            'add to collection 200': (r) => r.status === 200,
          });
        }
      });

      group('query_collection', () => {
        const res = graphqlQuery(
          `query GetCollection($id: String!) {
            content { collection { find(collection: $id) { id name metadataCount } } }
          }`,
          { id: col.id },
          auth
        );
        check(res, {
          'query collection 200': (r) => r.status === 200,
        });
      });
    }
  });

  sleep(0.3);
}

export function concurrentWrites(data) {
  if (!data.token) { sleep(1); return; }
  const auth = authHeaders(data.token);

  // Alternate between collection and metadata creation
  if (__ITER % 2 === 0) {
    const col = createCollection(auth, `conc-${__ITER}`);
    check(col.res, {
      'concurrent collection 200': (r) => r.status === 200,
    });
  } else {
    const meta = createMetadataItem(auth, `conc-${__ITER}`, null);
    check(meta.res, {
      'concurrent metadata 200': (r) => r.status === 200,
    });
  }
  sleep(0.1);
}

export default function (data) {
  createCollections(data);
  createMetadata(data);
  mixedCrud(data);
}
