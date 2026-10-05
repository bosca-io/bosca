alter table collections
    add column locked boolean default false not null;

alter table collections
    add column items_locked boolean default false not null;

alter table metadata
    add column locked boolean default false not null;

