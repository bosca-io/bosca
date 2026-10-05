import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BOSCA_BENCHMARK_URL || 'http://127.0.0.1:9090';
const workload = __ENV.BOSCA_BENCHMARK_WORKLOAD || 'plaintext';
const postBody = 'a'.repeat(1024);

export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: Number(__ENV.BOSCA_BENCHMARK_CONNECTIONS || 64),
      duration: __ENV.BOSCA_BENCHMARK_DURATION || '20s',
      gracefulStop: '0s',
    },
  },
  discardResponseBodies: true,
};

export default function () {
  let response;
  switch (workload) {
    case 'post1KiB':
      response = http.post(`${baseUrl}/echo`, postBody, {
        headers: { 'Content-Type': 'application/octet-stream' },
      });
      break;
    case 'gzip32KiB':
      response = http.get(`${baseUrl}/compressed`, {
        headers: { 'Accept-Encoding': 'gzip' },
      });
      break;
    default:
      response = http.get(`${baseUrl}/plaintext`);
  }
  check(response, { 'status is 200': (result) => result.status === 200 });
}
