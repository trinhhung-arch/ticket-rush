create table event
(
    id            uuid primary key,
    organizer_id  varchar(100) not null,
    name          varchar(200) not null,
    venue         varchar(200) not null,
    city          varchar(100) not null,
    starts_at     timestamptz  not null,
    sales_open_at timestamptz  not null,
    status        varchar(20)  not null,
    created_at    timestamptz  not null,
    published_at  timestamptz,
    version       bigint       not null
);

create index event_status_starts_at_idx on event (status, starts_at);

create table event_section
(
    id            uuid primary key,
    event_id      uuid         not null references event (id) on delete cascade,
    code          varchar(8)   not null,
    name          varchar(100) not null,
    row_count     integer      not null,
    seats_per_row integer      not null,
    price_vnd     bigint       not null,
    sort_order    integer      not null,
    unique (event_id, code)
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
