-- Durable control-plane state for generic jobs dispatched through the
-- physical `kubernetes-jobs` queue.

create type kubernetes.job_execution_status as enum (
    'queued',
    'materialized',
    'running',
    'succeeded',
    'failed',
    'cancel_requested',
    'cancelled'
);

create table kubernetes.job_execution (
    dispatch_id      uuid primary key,
    profile          text        not null,
    idempotency_key  text        not null,
    request          jsonb       not null,
    status           kubernetes.job_execution_status not null default 'queued',
    cluster_id       uuid references kubernetes.cluster (id),
    namespace        text,
    job_name         text,
    message          text,
    created_at       timestamptz not null default now(),
    modified_at      timestamptz not null default now(),
    published_at     timestamptz,
    materialized_at  timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    check (
        (cluster_id is null and namespace is null and job_name is null)
        or (cluster_id is not null and namespace is not null and job_name is not null)
    )
);

-- The complete request is an outbox payload only until the workload is materialized or terminal.
-- Retain only non-secret correlation identity once no retry can need environment, arguments,
-- labels, or annotations. Generic workloads may use credentials with names unknown to Bosca.
create function kubernetes.sanitize_job_request(value jsonb)
returns jsonb
language sql
immutable
strict
as $$
    select case
        when jsonb_typeof(value) = 'object' then jsonb_strip_nulls(
            jsonb_build_object(
                'profile', value -> 'profile',
                'idempotencyKey', value -> 'idempotencyKey'
            )
        )
        else value
    end
$$;

create unique index job_execution_idempotency_idx
    on kubernetes.job_execution (profile, idempotency_key);

create index job_execution_cancellation_idx
    on kubernetes.job_execution (created_at)
    where status = 'cancel_requested';

create index job_execution_terminal_idx
    on kubernetes.job_execution (modified_at)
    where status in ('succeeded', 'failed', 'cancelled');

create index job_execution_publication_idx
    on kubernetes.job_execution (modified_at)
    where status = 'queued' and published_at is null;
