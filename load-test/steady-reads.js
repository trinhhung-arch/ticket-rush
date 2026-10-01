// Steady public traffic for the Kubernetes pod drills (NFR-AVAIL-04, NFR-AVAIL-06): the event list
// (event-service) and a seat map (booking-service), 50 requests per second each. Every failed
// request is logged with its time, so the drill script can measure how long errors lasted.
//   k6 run -e GATEWAY=http://localhost:28080 -e EVENT_ID=<id> load-test/steady-reads.js
import http from 'k6/http';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:28080';
const DURATION = __ENV.DURATION || '100s';

export const options = {
  scenarios: {
    events: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 50, maxVUs: 400, exec: 'events' },
    seats: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: 50, maxVUs: 400, exec: 'seats' },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function record(response, service) {
  if (response.status !== 200) {
    console.warn(`FAILED ${Date.now()} ${service} ${response.status} ${response.error || ''}`);
  }
}

export function events() {
  record(http.get(`${GATEWAY}/api/events?size=5`, { tags: { name: 'events' } }), 'event-service');
}

export function seats() {
  record(http.get(`${GATEWAY}/api/events/${__ENV.EVENT_ID}/seats`, { tags: { name: 'seats' } }), 'booking-service');
}
