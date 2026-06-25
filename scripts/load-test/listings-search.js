import http from 'k6/http';
import { check } from 'k6';

// NFR-PERF-1 (M1.7.1, issue #37): GET /api/v1/listings must keep p95 < 500ms
// and p99 < 1000ms under 50 concurrent users, with < 0.1% errors.
// Run against a database seeded with >=1000 listings (see seed-listings.sql).
// /api/v1/** requires a valid JWT — pass one via AUTH_TOKEN (see docs/local-setup.md).
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';

export const options = {
  scenarios: {
    listings_search: {
      executor: 'constant-vus',
      vus: 50,
      duration: '5m',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/listings?dealType=RENT&page=0&size=20`, {
    headers: AUTH_TOKEN ? { Authorization: `Bearer ${AUTH_TOKEN}` } : {},
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
}
