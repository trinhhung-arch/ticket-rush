-- Local copy of published events, fed by EventPublished.
create table event_info
(
    id            uuid primary key,
    name          varchar(200) not null,
    venue         varchar(200) not null,
    city          varchar(100) not null,
    starts_at     timestamptz  not null,
    sales_open_at timestamptz  not null,
    total_seats   integer      not null,
    received_at   timestamptz  not null
);

-- One row per seat. A seat becomes SOLD only through a conditional update, the final guard
-- against overselling even if Redis loses its holds.
create table seat_inventory
(
    event_id     uuid        not null references event_info (id),
    seat_code    varchar(20) not null,
    seat_index   integer     not null,
    section_code varchar(8)  not null,
    price_vnd    bigint      not null,
    status       varchar(20) not null default 'AVAILABLE',
    booking_id   uuid,
    primary key (event_id, seat_code)
);

create table booking
(
    id              uuid primary key,
    event_id        uuid         not null references event_info (id),
    user_id         varchar(100) not null,
    email           varchar(320) not null,
    status          varchar(20)  not null,
    cancel_reason   varchar(30),
    total_vnd       bigint       not null,
    idempotency_key varchar(100) not null unique,
    expires_at      timestamptz  not null,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null
);

create index booking_user_idx on booking (user_id, created_at desc);
create index booking_open_expiry_idx on booking (expires_at) where status in ('PENDING', 'AWAITING_PAYMENT');

create table booking_seat
(
    booking_id uuid        not null references booking (id) on delete cascade,
    seat_code  varchar(20) not null,
    price_vnd  bigint      not null,
    primary key (booking_id, seat_code)
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
