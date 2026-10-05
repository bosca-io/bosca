-- Work Ops — Phase 6 (specs/workops/plan.md §6)
--
-- Lands the SavedFilter store backing R10's saved filters. Phase 6
-- ships the owner-only sharing model; visibility / role-shared /
-- project-shared flavors hook in alongside the Phase 7 permission
-- scheme.

create table workops.saved_filter (
    id                  uuid        not null default gen_random_uuid() primary key,
    owner_profile_id    uuid        not null,
    name                varchar     not null,
    description         varchar,
    -- The original BQL source the caller submitted. Round-trips
    -- through the parser produce [parsed_ast]; the source stays so
    -- admin tooling can render the human-authored form back.
    bql_source          text        not null,
    -- The parser AST as JSON (see core-workops bql.BqlQuery). The
    -- planner re-validates on read in case the field catalog has
    -- changed since the filter was saved.
    parsed_ast          jsonb       not null,
    created_at          timestamptz not null default now(),
    modified_at         timestamptz not null default now(),
    version             bigint      not null default 0,
    constraint saved_filter_unique_within_owner unique (owner_profile_id, name)
);

create index saved_filter_owner_idx on workops.saved_filter(owner_profile_id);
