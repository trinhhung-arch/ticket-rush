// Helpers shared by the k6 scripts.
import http from 'k6/http';
import { sleep } from 'k6';

export const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
export const ORGANIZER = 'organizer-load-test';

export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function iso(offsetMs) {
  return new Date(Date.now() + offsetMs).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** Five sections of 10 rows x 100 seats: 5,000 seats, the size NFR-PERF-03 is written for. */
export const SECTIONS = ['A', 'B', 'C', 'D', 'E'].map((code) => ({
  code, name: `Khu ${code}`, rows: 10, seatsPerRow: 100, priceVnd: 1000000,
}));

export function seatCodes() {
  const rows = 'ABCDEFGHIJ'.split('');
  const codes = [];
  for (const section of SECTIONS) {
    for (const row of rows.slice(0, section.rows)) {
      for (let n = 1; n <= section.seatsPerRow; n++) {
        codes.push(`${section.code}-${row}-${String(n).padStart(2, '0')}`);
      }
    }
  }
  return codes;
}

/** Creates and publishes an event, then waits until booking-service has its seat map. */
export function createEvent(name, waitingRoom = false) {
  const headers = { 'Content-Type': 'application/json', 'X-User-Id': ORGANIZER };
  const created = http.post(`${GATEWAY}/api/events`, JSON.stringify({
    name, venue: 'Sân vận động Mỹ Đình', city: 'Hanoi',
    startsAt: iso(30 * 24 * 3600 * 1000), salesOpenAt: iso(-3600 * 1000),
    waitingRoom, sections: SECTIONS,
  }), { headers });
  if (created.status !== 201) {
    throw new Error(`could not create event: ${created.status} ${created.body}`);
  }
  const eventId = created.json('id');
  http.post(`${GATEWAY}/api/events/${eventId}/publish`, null, { headers });
  for (let i = 0; i < 60; i++) {
    const map = http.get(`${GATEWAY}/api/events/${eventId}/seats`);
    if (map.status === 200 && map.json('total') === 5000) {
      return eventId;
    }
    sleep(1);
  }
  throw new Error(`seat map of ${eventId} never appeared`);
}
