// Flash sale (NFR-PERF-01, NFR-PERF-02, NFR-CORR-01): 1,000 hold requests per second for random
// seats of a 5,000-seat event; every buyer who gets a seat pays, after 5-15 s filling in the
// payment form like a real customer.
//   k6 run -e GATEWAY=http://localhost:8080 -e LOAD_TEST_JWT_SECRET=... -e RATE=1000 -e DURATION=5m load-test/flash-sale.js
// (run-flash-sale.sh passes the secret from .env; see load-test/README.md)
// Then check nothing was sold twice: ./scripts/check-oversell.sh <eventId printed below>
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { GATEWAY, bearer, createEvent, seatCodes, uuid } from './lib.js';

const RATE = Number(__ENV.RATE || 1000);
const DURATION = __ENV.DURATION || '1m';
// How long a buyer keeps the checkout page open. The chaos runs raise it past the outage.
const PAY_PATIENCE = Number(__ENV.PAY_PATIENCE || 30);

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
      // Buyers still paying when the arrival phase ends get this long to finish.
      gracefulStop: __ENV.GRACEFUL_STOP || '30s',
      preAllocatedVUs: Number(__ENV.VUS || 7000),
      maxVUs: Number(__ENV.VUS || 7000) + 2000,
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
  const auth = bearer(user);
  const seat = data.seats[Math.floor(Math.random() * data.seats.length)];
  const hold = http.post(`${GATEWAY}/api/bookings`,
    JSON.stringify({ eventId: data.eventId, seatCodes: [seat] }),
    { headers: { 'Content-Type': 'application/json', Authorization: auth, 'Idempotency-Key': uuid() }, tags: { name: 'hold' } });
  if (hold.status === 409) {
    conflicts.add(1);
    return;
  }
  if (hold.status !== 201) {
    return;
  }
  held.add(1);
  sleep(5 + Math.random() * 10);
  pay(auth, hold.json('id'));
}

/**
 * Polls like a checkout page would, once a second for up to PAY_PATIENCE s, then pays through the
 * mock gateway. A 503 or 504 (payment-service down, circuit open) is retried like a customer would.
 */
function pay(auth, bookingId) {
  for (let i = 0; i < PAY_PATIENCE; i++) {
    sleep(1);
    const booking = http.get(`${GATEWAY}/api/bookings/${bookingId}`,
      { headers: { Authorization: auth }, tags: { name: 'poll' } });
    const checkoutUrl = booking.status === 200 ? booking.json('checkoutUrl') : null;
    if (!checkoutUrl) {
      continue;
    }
    const result = http.post(checkoutUrl, JSON.stringify({ outcome: 'SUCCEEDED' }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'pay' } });
    if (result.status === 503 || result.status === 504) {
      continue;
    }
    if (result.status === 200 && result.json('payment.status') === 'SUCCEEDED') {
      paid.add(1);
    }
    return;
  }
}

export function teardown(data) {
  sleep(5);
  const map = http.get(`${GATEWAY}/api/events/${data.eventId}/seats`).json();
  console.log(`EVENT_ID=${data.eventId} seats: total=${map.total} sold=${map.sold} held=${map.held} available=${map.available}`);
}
