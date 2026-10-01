import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, graphqlQuery, login, authHeaders, defaultHeaders, defaultThresholds } from './config.js';

const isSmoke = __ENV.SMOKE === 'true';

const TEST_USER = __ENV.TEST_USER || 'admin';
const TEST_PASS = __ENV.TEST_PASS || 'password';

export const options = isSmoke
  ? { vus: 1, duration: '10s', thresholds: { http_req_failed: ['rate<0.10'] } }
  : {
      scenarios: {
        large_graphql_variables: {
          executor: 'constant-vus',
          vus: 20,
          duration: '1m',
          exec: 'largeGraphqlVariables',
          tags: { scenario: 'large_graphql_variables' },
        },
        upload_1mb: {
          executor: 'constant-vus',
          vus: 10,
          duration: '1m',
          startTime: '1m30s',
          exec: 'upload1MB',
          tags: { scenario: 'upload_1mb' },
        },
        upload_10mb: {
          executor: 'constant-vus',
          vus: 5,
          duration: '1m',
          startTime: '3m',
          exec: 'upload10MB',
          tags: { scenario: 'upload_10mb' },
        },
        concurrent_uploads: {
          executor: 'constant-vus',
          vus: 20,
          duration: '1m',
          startTime: '4m30s',
          exec: 'concurrentUploads',
          tags: { scenario: 'concurrent_uploads' },
        },
      },
      thresholds: Object.assign({}, defaultThresholds, {
        'http_req_duration{scenario:large_graphql_variables}': ['p(95)<1000'],
        'http_req_duration{scenario:upload_1mb}': ['p(95)<2000'],
        'http_req_duration{scenario:upload_10mb}': ['p(95)<10000'],
        'http_req_duration{scenario:concurrent_uploads}': ['p(95)<5000'],
      }),
    };

export function setup() {
  const token = login(TEST_USER, TEST_PASS);
  return { token };
}

/**
 * Generates a string of approximately the given size in bytes.
 */
function generatePayload(sizeBytes) {
  const chunk = 'abcdefghijklmnopqrstuvwxyz0123456789';
  const repeats = Math.ceil(sizeBytes / chunk.length);
  return chunk.repeat(repeats).substring(0, sizeBytes);
}

export function largeGraphqlVariables() {
  // Send a GraphQL query with a large variables payload (~1MB)
  // Uses server { now } since the variables content doesn't matter for throughput testing
  const largeValue = generatePayload(1024 * 1024); // 1MB
  const res = graphqlQuery(
    '{ server { now } }',
    { _padding: largeValue }
  );
  check(res, {
    'large variables 200': (r) => r.status === 200,
  });
  sleep(0.5);
}

export function upload1MB(data) {
  if (!data.token) { sleep(1); return; }
  doUpload(data, 1024 * 1024, 'upload_1mb');
}

export function upload10MB(data) {
  if (!data.token) { sleep(1); return; }
  doUpload(data, 10 * 1024 * 1024, 'upload_10mb');
}

export function concurrentUploads(data) {
  if (!data.token) { sleep(1); return; }
  doUpload(data, 1024 * 1024, 'concurrent_upload');
}

function doUpload(data, sizeBytes, label) {
  const auth = authHeaders(data.token);

  // First create a metadata item to get an upload URL
  const name = `k6-upload-${label}-${Date.now()}-${__VU}`;
  const createRes = graphqlQuery(
    `mutation CreateMetadata($input: MetadataInput!) {
      content { metadata { add(metadata: $input) { id name } } }
    }`,
    {
      input: {
        name,
        contentType: 'application/octet-stream',
        languageTag: 'en',
        contentLength: sizeBytes,
      },
    },
    auth
  );

  check(createRes, {
    [`${label} create metadata 200`]: (r) => r.status === 200,
  });

  const createBody = JSON.parse(createRes.body);
  const metadataId = createBody.data?.content?.metadata?.add?.id;
  if (!metadataId) {
    return;
  }

  // Upload content via the metadata upload endpoint
  const fileData = generatePayload(sizeBytes);
  const uploadRes = http.post(
    `${BASE_URL}/api/v1/content/metadata/upload?id=${metadataId}`,
    {
      file: http.file(fileData, `${name}.bin`, 'application/octet-stream'),
    },
    {
      headers: Object.assign({}, { Authorization: auth.Authorization }),
    }
  );

  check(uploadRes, {
    [`${label} upload status ok`]: (r) => r.status >= 200 && r.status < 300,
  });

  // Mark metadata as ready
  const readyRes = graphqlQuery(
    `mutation SetMetadataReady($id: UUID!) {
      content { metadata { setMetadataReady(id: $id) } }
    }`,
    { id: metadataId },
    auth
  );

  check(readyRes, {
    [`${label} set ready 200`]: (r) => r.status === 200,
  });

  // Transition to published
  const publishRes = graphqlQuery(
    `mutation SetWorkflowState($state: MetadataWorkflowState!) {
      content { metadata { setWorkflowState(state: $state) } }
    }`,
    {
      state: {
        metadataId,
        stateId: 'published',
        status: 'k6 load test publish',
        immediate: true,
      },
    },
    auth
  );

  check(publishRes, {
    [`${label} publish 200`]: (r) => r.status === 200,
  });

  sleep(0.5);
}

export default function (data) {
  largeGraphqlVariables();
  upload1MB(data);
}
