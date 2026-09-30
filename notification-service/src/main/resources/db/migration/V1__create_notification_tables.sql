create table sent_email
(
    id         uuid primary key,
    booking_id uuid         not null,
    kind       varchar(30)  not null,
    recipient  varchar(320) not null,
    subject    varchar(300) not null,
    sent_at    timestamptz  not null,
    unique (booking_id, kind)
);

-- Chassis tables used by the common module (Transactional Outbox, Idempotent Consumer).
create table outbox_message
(
    id           uuid primary key,
    seq          bigint generated always as identity,
    topic        varchar(200) not null,
    message_key  varchar(200) not null,
    message_type varchar(100) not null,
    payload      text         not null,
    created_at   timestamptz  not null,
    published_at timestamptz
);

create index outbox_message_unpublished_idx on outbox_message (seq) where published_at is null;

create table processed_message
(
    message_id   uuid primary key,
    message_type varchar(100) not null,
    processed_at timestamptz  not null
);
