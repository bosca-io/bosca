-- Personalization Signal definitions (EXP-SPEC-3): admin config for how profile attributes / segments
-- personalize recommendations. Each definition derives a keyed, typed value via a JSONata expression; the
-- value is computed at attribute write-time and cached on the attribute, then read (keyed) by the trainer's
-- user tower + the cohort builder.
create type recommendations.signal_source_type as enum ('attribute', 'segment');
create type recommendations.signal_value_type as enum ('categorical', 'multi_categorical', 'numeric', 'boolean');

create table recommendations.personalization_signals (
    id              uuid primary key default gen_random_uuid(),
    key             text not null unique,
    source_type     recommendations.signal_source_type not null,
    source_id       text not null,
    expression      text not null,
    value_type      recommendations.signal_value_type not null,
    priority        int not null default 0,
    use_as_feature  boolean not null default true,
    use_as_cohort   boolean not null default false,
    enabled         boolean not null default true,
    created         timestamptz not null default now(),
    modified        timestamptz not null default now()
);

-- The write-time compute pipeline looks up enabled definitions by (source_type, source_id) on each attribute event.
create index idx_personalization_signals_source on recommendations.personalization_signals (source_type, source_id) where enabled;
