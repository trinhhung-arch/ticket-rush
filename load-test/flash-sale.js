// Flash sale (NFR-PERF-01, NFR-PERF-02, NFR-CORR-01): 1,000 hold requests per second for random
// seats of a 5,000-seat event; every buyer who gets a seat pays, after 5-15 s filling in the
// payment form like a real customer.
//   k6 run -e GATEWAY=http://localhost:8080 -e RATE=1000 -e DURATION=5m load-test/flash-sale.js
// Then check nothing was sold twice: ./scripts/check-oversell.sh <eventId printed below>
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { GATEWAY, createEvent, seatCodes, uuid } from './lib.js';

const RATE = Number(__ENV.RATE || 1000);
const DURATION = __ENV.DURATION || '1m';

const held = new Counter('holds_won');
const conflicts = new Counter('holds_conflict');
const paid = new Counter('payments_succeeded');

export const options = {
  scenarios: {
    buyers: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // Winners keep their VU for 5-45 s while paying, so allocate them all up front.
      preAllocatedVUs: 7000,
      maxVUs: 9000,
    },
  },
  thresholds: {
    'http_req_duration{name:hold}': ['p(95)<200', 'p(99)<500'],
    // 409 means "seat taken", the expected answer for most buyers once the sale is on.
    'http_req_failed{name:hold}': ['rate<0.001'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export function setup() {
  const eventId = createEvent(`Flash sale ${new Date().toISOString()}`);
  console.log(`EVENT_ID=${eventId}`);
  return { eventId, seats: seatCodes() };
}

export default function (data) {
  const user = `buyer-${uuid()}`;
  const seat = data.seats[Math.floor(Math.random() * data.seats.length)];
  const hold = http.post(`${GATEWAY}/api/bookings`,
    JSON.stringify({ eventId: data.eventId, seatCodes: [seat], email: `${user}@example.com` }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': user, 'Idempotency-Key': uuid() }, tags: { name: 'hold' } });
  if (hold.status === 409) {
    conflicts.add(1);
    return;
  }
  if (hold.status !== 201) {
    return;
  }
  held.add(1);
  sleep(5 + Math.random() * 10);
  pay(user, hold.json('id'));
}

/** Polls like a checkout page would, once a second for up to 30 s, then pays through the mock gateway. */
function pay(user, bookingId) {
  for (let i = 0; i < 30; i++) {
    sleep(1);
    const booking = http.get(`${GATEWAY}/api/bookings/${bookingId}`,
      { headers: { 'X-User-Id': user }, tags: { name: 'poll' } });
    const checkoutUrl = booking.status === 200 ? booking.json('checkoutUrl') : null;
    if (checkoutUrl) {
      const result = http.post(checkoutUrl, JSON.stringify({ outcome: 'SUCCEEDED' }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'pay' } });
      if (result.status === 200 && result.json('payment.status') === 'SUCCEEDED') {
        paid.add(1);
      }
      return;
    }
  }
}

export function teardown(data) {
  sleep(5);
  const map = http.get(`${GATEWAY}/api/events/${data.eventId}/seats`).json();
  console.log(`EVENT_ID=${data.eventId} seats: total=${map.total} sold=${map.sold} held=${map.held} available=${map.available}`);
}
