create table ticket
(
    id            uuid primary key,
    booking_id    uuid         not null,
    event_id      uuid         not null,
    user_id       varchar(100) not null,
    seat_code     varchar(20)  not null,
    event_name    varchar(200) not null,
    venue         varchar(200) not null,
    starts_at     timestamptz  not null,
    qr_token      varchar(200) not null unique,
    issued_at     timestamptz  not null,
    checked_in_at timestamptz,
    unique (booking_id, seat_code)
);

create index ticket_user_idx on ticket (user_id, starts_at);

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
