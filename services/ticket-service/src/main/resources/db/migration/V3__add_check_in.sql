-- FR-TKT-03: who let a ticket in, and which account organizes each event, learned from EventPublished.
alter table ticket add column checked_in_by varchar(100);

create table event_organizer
(
    event_id     uuid primary key,
    organizer_id varchar(100) not null
);
