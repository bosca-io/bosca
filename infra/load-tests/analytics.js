import http from 'k6/http';
import { check, sleep } from 'k6';
import { login, authHeaders, defaultHeaders, defaultThresholds } from './config.js';

const ANALYTICS_URL = __ENV.ANALYTICS_URL || 'http://localhost:8081';
const isSmoke = __ENV.SMOKE === 'true';

const TEST_USER = __ENV.TEST_USER || 'admin';
const TEST_PASS = __ENV.TEST_PASS || 'password';

export const options = isSmoke
  ? { vus: 1, duration: '10s', thresholds: { http_req_failed: ['rate<0.10'] } }
  : {
      scenarios: {
        installation: {
          executor: 'constant-vus',
          vus: 10,
          duration: '30s',
          exec: 'registerInstallation',
          tags: { scenario: 'installation' },
        },
        single_events: {
          executor: 'ramping-vus',
          startVUs: 5,
          startTime: '30s',
          stages: [
            { duration: '30s', target: 50 },
            { duration: '1m', target: 50 },
            { duration: '30s', target: 0 },
          ],
          exec: 'sendSingleEvent',
          tags: { scenario: 'single_events' },
        },
        batch_events: {
          executor: 'constant-vus',
          vus: 20,
          duration: '1m',
          startTime: '2m30s',
          exec: 'sendBatchEvents',
          tags: { scenario: 'batch_events' },
        },
        burst_events: {
          executor: 'constant-vus',
          vus: 50,
          duration: '30s',
          startTime: '4m',
          exec: 'sendBurstEvents',
          tags: { scenario: 'burst_events' },
        },
        flush: {
          executor: 'per-vu-iterations',
          vus: 1,
          iterations: 1,
          startTime: '5m',
          exec: 'flushEvents',
          tags: { scenario: 'flush' },
        },
      },
      thresholds: Object.assign({}, defaultThresholds, {
        'http_req_duration{scenario:installation}': ['p(95)<200'],
        'http_req_duration{scenario:single_events}': ['p(95)<200'],
        'http_req_duration{scenario:batch_events}': ['p(95)<500'],
        'http_req_duration{scenario:burst_events}': ['p(95)<1000'],
        'http_req_duration{scenario:flush}': ['p(95)<5000'],
      }),
    };

export function setup() {
  const token = login(TEST_USER, TEST_PASS);
  return { token };
}

const EVENT_TYPES = ['session', 'interaction', 'impression', 'completion', 'error'];

function makeContext(installationId) {
  return {
    app_id: 'k6-load-test',
    app_version: '1.0.0',
    device: {
      installation_id: installationId || 'k6-default-installation',
      manufacturer: 'k6',
      model: 'load-tester',
      platform: 'test',
      primary_locale: 'en-US',
      system_name: 'k6',
      timezone: 'UTC',
      type: 'desktop',
      version: '1.0',
    },
    session_id: `k6-session-${__VU}-${__ITER}`,
  };
}

function makeEvent(type) {
  const now = Date.now();
  const event = {
    created: now,
    created_micros: 0,
    type: type || EVENT_TYPES[Math.floor(Math.random() * EVENT_TYPES.length)],
    element: {
      id: `element-${__VU}-${__ITER}`,
      type: 'test-element',
    },
  };
  if (event.type === 'error') {
    event.error = {
      message: 'k6 test error',
      type: 'TestError',
      fatal: false,
    };
  }
  return event;
}

function makeEventsPayload(count, installationId) {
  const now = Date.now();
  const events = [];
  for (let i = 0; i < count; i++) {
    events.push(makeEvent());
  }
  return {
    context: makeContext(installationId),
    events,
    sent: now,
    sent_micros: 0,
  };
}

function postEvents(payload) {
  return http.post(`${ANALYTICS_URL}/api/v1/events`, JSON.stringify(payload), {
    headers: defaultHeaders,
  });
}

export function registerInstallation() {
  const res = http.post(`${ANALYTICS_URL}/api/v1/installation`, null, {
    headers: defaultHeaders,
  });
  check(res, {
    'installation status 200': (r) => r.status === 200,
    'installation has id': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.id && body.id.length > 0;
      } catch (_e) {
        return false;
      }
    },
  });
  sleep(0.2);
}

export function sendSingleEvent() {
  const payload = makeEventsPayload(1);
  const res = postEvents(payload);
  check(res, {
    'single event accepted': (r) => r.status === 200 || r.status === 202,
  });
  sleep(0.1);
}

export function sendBatchEvents() {
  const payload = makeEventsPayload(20);
  const res = postEvents(payload);
  check(res, {
    'batch events accepted': (r) => r.status === 200 || r.status === 202,
  });
  sleep(0.3);
}

export function sendBurstEvents() {
  const payload = makeEventsPayload(5);
  const res = postEvents(payload);
  check(res, {
    'burst event accepted': (r) => r.status === 200 || r.status === 202,
  });
}

export function flushEvents(data) {
  if (!data.token) { return; }
  const res = http.get(`${ANALYTICS_URL}/api/v1/events/flush`, {
    headers: Object.assign({}, defaultHeaders, authHeaders(data.token)),
  });
  check(res, {
    'flush status ok': (r) => r.status >= 200 && r.status < 300,
  });
}

export default function () {
  registerInstallation();
  sendSingleEvent();
  sendBatchEvents();
}
