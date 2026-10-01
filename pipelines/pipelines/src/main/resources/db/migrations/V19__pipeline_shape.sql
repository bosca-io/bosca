-- Named reusable object shapes: a first-class type an Input/Output references by name (stored in the
-- graph as "shape:<name>"), so a map of typed fields is defined once (e.g. ReleaseBundle) and reused
-- across pipelines. Global, keyed by name; fields is a jsonb array of { name, type }.
create table pipelines.pipeline_shape
(
    name        text        not null primary key,
    fields      jsonb       not null default '[]',
    created_at  timestamptz not null default now(),
    modified_at timestamptz not null default now()
);
