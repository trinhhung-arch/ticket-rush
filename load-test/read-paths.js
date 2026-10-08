// Read paths (NFR-PERF-03): the event list and the 5,000-seat map under steady load.
//   k6 run -e GATEWAY=http://localhost:8080 load-test/read-paths.js
import http from 'k6/http';
import { GATEWAY, createEvent } from './lib.js';

export const options = {
  scenarios: {
    eventList: {
      executor: 'constant-arrival-rate', rate: 200, timeUnit: '1s', duration: '60s',
      preAllocatedVUs: 50, maxVUs: 500, exec: 'eventList',
    },
    seatMap: {
      executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '60s',
      preAllocatedVUs: 50, maxVUs: 500, exec: 'seatMap',
    },
  },
  thresholds: {
    'http_req_duration{name:events}': ['p(95)<150'],
    'http_req_duration{name:seats}': ['p(95)<300'],
    'http_req_failed': ['rate<0.001'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { eventId: createEvent(`Read paths ${new Date().toISOString()}`) };
}

export function eventList() {
  http.get(`${GATEWAY}/api/events?city=Hanoi&size=20`, { tags: { name: 'events' } });
}

export function seatMap(data) {
  http.get(`${GATEWAY}/api/events/${data.eventId}/seats`, { tags: { name: 'seats' } });
}
