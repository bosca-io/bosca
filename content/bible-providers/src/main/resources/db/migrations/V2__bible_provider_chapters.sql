create table bible_providers.chapters (
    source_id uuid not null references bible_providers.sources (id) on delete cascade,
    provider varchar not null,
    usfm varchar not null,
    content jsonb not null check (content -> 'reference' ->> 'usfm' = usfm),
    attribution jsonb,
    verse_count integer not null check (verse_count >= 0),
    fums_token varchar,
    created timestamptz not null,
    expires timestamptz not null check (expires > created and expires <= created + interval '14 days'),
    primary key (source_id, usfm)
);

create index chapters_expiry on bible_providers.chapters (expires);
