-- Bindings of scripts to analytics events. A binding makes the analytics processor run its script
-- inline over every event batch (see AnalyticsScriptTransform), bypassing the durable pipeline run
-- machinery. There is no event-type routing: the script receives the whole batch and filters itself.
--
-- Lives in the public schema like the rest of the analytics tables (see AnalyticsMigration). No
-- foreign key to the scripts table: scripts live in a different module/schema that may not be
-- migrated in every deployment, so the binding references a script by id and the service treats a
-- missing script as a skipped, logged no-op rather than a constraint violation.

create table if not exists public.analytics_script_binding
(
    id        uuid        not null primary key,
    script_id uuid        not null,
    -- true: the script's returned batch replaces the events flowing through (pass through / filter).
    -- false: the script runs for side effects only and its output is ignored.
    transform boolean     not null default true,
    enabled   boolean     not null default true,
    ordinal   integer     not null default 0,
    created   timestamptz not null default now(),
    modified  timestamptz not null default now()
);

-- Hot path: the processor loads enabled bindings in execution order on every batch.
create index if not exists analytics_script_binding_enabled_ordinal_idx
    on public.analytics_script_binding (ordinal)
    where enabled;
