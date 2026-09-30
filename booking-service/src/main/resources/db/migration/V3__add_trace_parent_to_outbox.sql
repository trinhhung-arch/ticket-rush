-- W3C traceparent of the transaction that wrote the message; the relay continues that trace (NFR-OBS-01).
alter table outbox_message add column trace_parent varchar(100);
