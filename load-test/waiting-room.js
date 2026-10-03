// Waiting room capacity (NFR-PERF-05): 50,000 buyers join one event's queue within a minute.
//   k6 run -e GATEWAY=http://localhost:8080 -e LOAD_TEST_JWT_SECRET=... load-test/waiting-room.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { GATEWAY, bearer, createEvent, uuid } from './lib.js';

const JOINS = Number(__ENV.JOINS || 50000);
const admitted = new Counter('joins_admitted');
const queued = new Counter('joins_queued');

export const options = {
  scenarios: {
    rush: {
      executor: 'constant-arrival-rate',
      rate: Math.ceil(JOINS / 60),
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
    },
  },
  thresholds: {
    'http_req_duration{name:join}': ['p(95)<200'],
    'http_req_failed{name:join}': ['rate<0.001'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const eventId = createEvent(`Waiting room ${new Date().toISOString()}`, true);
  console.log(`EVENT_ID=${eventId}`);
  return { eventId };
}

export default function (data) {
  const response = http.post(`${GATEWAY}/api/queue/events/${data.eventId}/join`, null,
    { headers: { Authorization: bearer(`fan-${uuid()}`) }, tags: { name: 'join' } });
  const ok = check(response, { 'joined': (r) => r.status === 200 });
  if (ok) {
    (response.json('state') === 'ADMITTED' ? admitted : queued).add(1);
  }
}
