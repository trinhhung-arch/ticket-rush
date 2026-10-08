create table payment
(
    id                     uuid primary key,
    booking_id             uuid         not null unique,
    user_id                varchar(100) not null,
    amount_vnd             bigint       not null,
    status                 varchar(20)  not null,
    expires_at             timestamptz  not null,
    gateway_transaction_id varchar(100),
    status_reason          varchar(50),
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    version                bigint       not null
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
