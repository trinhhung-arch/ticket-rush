#!/usr/bin/env python3
"""End-of-sale reconciliation across the service databases (NFR-CORR-04, NFR-CORR-02).

    ./scripts/reconcile.py <eventId>

Reads booking_db, payment_db, ticket_db and notify_db (each with its own service account, the way
an auditor with read access would) and checks, for one event:
  - every booking whose hold has ended is in a final state (CONFIRMED or CANCELLED);
  - CONFIRMED <=> payment SUCCEEDED; no CANCELLED booking keeps the customer's money;
  - every CONFIRMED booking got one ticket per seat and a ticket email (nothing lost on Kafka);
  - no seat is sold twice; no outbox row is left unpublished in any service.
Exits 1 when any check fails.
"""
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRACE_SECONDS = 60


def env():
    values = dict(os.environ)
    dotenv = ROOT / ".env"
    if dotenv.exists():
        for line in dotenv.read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                values.setdefault(key, value)
    return values


ENV = env()


def query(db, user, password_var, sql):
    """Rows as lists of strings; SQL goes in on stdin so long id lists are fine."""
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", f"PGPASSWORD={ENV[password_var]}", "postgres",
         "psql", "-h", "localhost", "-U", user, "-d", db, "-tA", "-F", "\t", "-v", "ON_ERROR_STOP=1", "-f", "-"],
        input=sql, capture_output=True, text=True, cwd=ROOT, check=True)
    return [line.split("\t") for line in result.stdout.splitlines() if line]


def booking(sql):
    return query("booking_db", "booking_svc", "BOOKING_DB_PASSWORD", sql)


def payment(sql):
    return query("payment_db", "payment_svc", "PAYMENT_DB_PASSWORD", sql)


def ticket(sql):
    return query("ticket_db", "ticket_svc", "TICKET_DB_PASSWORD", sql)


def notify(sql):
    return query("notify_db", "notify_svc", "NOTIFY_DB_PASSWORD", sql)


def id_array(ids):
    return "'{" + ",".join(sorted(ids)) + "}'::uuid[]"


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: reconcile.py <eventId>")
    event_id = sys.argv[1]

    bookings = {row[0]: row for row in booking(f"""
        select b.id, b.status, coalesce(b.cancel_reason, ''),
               (b.expires_at + interval '{GRACE_SECONDS} seconds' < now())::text, count(s.seat_code)
        from booking b left join booking_seat s on s.booking_id = b.id
        where b.event_id = '{event_id}' group by b.id;""")}
    ids = set(bookings)
    payments = {row[0]: row[1] for row in payment(
        f"select booking_id, status from payment where booking_id = any({id_array(ids)});")} if ids else {}
    tickets = {row[0]: int(row[1]) for row in ticket(
        f"select booking_id, count(*) from ticket where event_id = '{event_id}' group by booking_id;")}
    confirmed = {i for i, row in bookings.items() if row[1] == "CONFIRMED"}
    emailed = {row[0] for row in notify(
        f"select booking_id from sent_email where kind = 'TICKETS' and booking_id = any({id_array(confirmed)});")} \
        if confirmed else set()
    oversold = int(booking(f"""
        select count(*) from (select s.seat_code from booking_seat s join booking b on b.id = s.booking_id
        where b.event_id = '{event_id}' and b.status = 'CONFIRMED' group by s.seat_code having count(*) > 1) d;""")[0][0])
    unpublished = {
        "booking": int(booking("select count(*) from outbox_message where published_at is null;")[0][0]),
        "payment": int(payment("select count(*) from outbox_message where published_at is null;")[0][0]),
        "ticket": int(ticket("select count(*) from outbox_message where published_at is null;")[0][0]),
    }

    states = Counter((row[1], row[2] or "-") for row in bookings.values())
    print(f"Event {event_id}: {len(bookings)} bookings")
    for (status, reason), count in sorted(states.items()):
        print(f"  {status:<17} {reason:<15} {count}")
    print("Payments by status: " + ", ".join(f"{s} {c}" for s, c in sorted(Counter(payments.values()).items())))

    open_holds = [i for i, row in bookings.items() if row[1] not in ("CONFIRMED", "CANCELLED") and row[3] == "false"]
    checks = [
        ("booking past its hold but not final", [i for i, row in bookings.items()
                                                  if row[1] not in ("CONFIRMED", "CANCELLED") and row[3] == "true"]),
        ("CONFIRMED without a SUCCEEDED payment", [i for i in confirmed if payments.get(i) != "SUCCEEDED"]),
        ("CANCELLED but the money was kept", [i for i, row in bookings.items()
                                              if row[1] == "CANCELLED" and payments.get(i) == "SUCCEEDED"]),
        ("CONFIRMED without one ticket per seat", [i for i in confirmed if tickets.get(i, 0) != int(bookings[i][4])]),
        ("CONFIRMED without a ticket email", [i for i in confirmed if i not in emailed]),
        ("seats sold to more than one booking", ["x"] * oversold),
        ("outbox rows never published", ["x"] * sum(unpublished.values())),
    ]
    print(f"Still inside their hold (not checked yet): {len(open_holds)}")
    failed = False
    for name, offenders in checks:
        mark = "ok  " if not offenders else "FAIL"
        failed |= bool(offenders)
        sample = f"  e.g. {offenders[0]}" if offenders and offenders[0] != "x" else ""
        print(f"  [{mark}] {name}: {len(offenders)}{sample}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
