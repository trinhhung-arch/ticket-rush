alter table booking
    add column payment_id   uuid,
    add column checkout_url varchar(500);
