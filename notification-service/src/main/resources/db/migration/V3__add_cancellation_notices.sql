-- FR-NTF-02: a refund email needs facts from two topics that may arrive in either order: the
-- customer's email and cancel reason (booking.events) and the refunded amount (payment.events).
create table booking_cancellation
(
    booking_id   uuid primary key,
    recipient    varchar(320) not null,
    reason       varchar(40)  not null,
    cancelled_at timestamptz  not null
);

create table payment_refund
(
    booking_id  uuid primary key,
    amount_vnd  bigint      not null,
    refunded_at timestamptz not null
);
