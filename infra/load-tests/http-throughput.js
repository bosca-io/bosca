import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, graphqlQuery, defaultThresholds } from './config.js';

const isSmoke = __ENV.SMOKE === 'true';

export const options = isSmoke
  ? { vus: 1, duration: '10s', thresholds: { http_req_failed: ['rate<0.05'] } }
  : {
      scenarios: {
        ready_check: {
          executor: 'ramping-vus',
          startVUs: 1,
          stages: [
            { duration: '30s', target: 50 },
            { duration: '1m', target: 50 },
            { duration: '30s', target: 200 },
            { duration: '1m', target: 200 },
            { duration: '30s', target: 0 },
          ],
          exec: 'readyCheck',
          tags: { scenario: 'ready_check' },
        },
        server_now: {
          executor: 'ramping-vus',
          startVUs: 1,
          startTime: '3m30s',
          stages: [
            { duration: '30s', target: 50 },
            { duration: '1m', target: 50 },
            { duration: '30s', target: 200 },
            { duration: '1m', target: 200 },
            { duration: '30s', target: 0 },
          ],
          exec: 'serverNow',
          tags: { scenario: 'server_now' },
        },
        health_check: {
          executor: 'constant-vus',
          vus: 50,
          duration: '1m',
          startTime: '7m',
          exec: 'healthCheck',
          tags: { scenario: 'health_check' },
        },
      },
      thresholds: Object.assign({}, defaultThresholds, {
        'http_req_duration{scenario:ready_check}': ['p(95)<50'],
        'http_req_duration{scenario:server_now}': ['p(95)<100'],
        'http_req_duration{scenario:health_check}': ['p(95)<200'],
      }),
    };

export function readyCheck() {
  const res = http.get(`${BASE_URL}/api/v1/ready`);
  check(res, {
    'ready status 200': (r) => r.status === 200,
    'ready body contains status': (r) => r.body.includes('ready'),
  });
  sleep(0.1);
}

export function serverNow() {
  const res = graphqlQuery('{ server { now } }');
  check(res, {
    'server now status 200': (r) => r.status === 200,
    'server now has data': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data && body.data.server && body.data.server.now;
      } catch (_e) {
        return false;
      }
    },
  });
  sleep(0.1);
}

export function healthCheck() {
  const res = http.get(`${BASE_URL}/api/v1/health`);
  check(res, {
    'health status 200': (r) => r.status === 200,
    'health body has ok': (r) => {
      try {
        return JSON.parse(r.body).ok === true;
      } catch (_e) {
        return false;
      }
    },
  });
  sleep(0.2);
}

export default function () {
  readyCheck();
  serverNow();
  healthCheck();
}
