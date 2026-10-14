create table workops.plan_creation (
    key uuid not null,
    principal_id uuid not null,
    fingerprint text not null,
    target_id uuid,
    created timestamptz not null default now(),
    primary key (principal_id, key)
);
