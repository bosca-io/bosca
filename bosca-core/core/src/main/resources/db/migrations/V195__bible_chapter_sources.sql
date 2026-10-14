alter table bible_chapters
    alter column components drop not null,
    add column source_id uuid,
    add column source_version bigint,
    add column attribution jsonb,
    add column verse_count integer check (verse_count >= 0),
    add column fums_token varchar,
    add column created timestamptz,
    add column expires timestamptz,
    add constraint bible_chapter_retention check (
        expires is null or (source_id is not null and created is not null
            and expires > created and expires <= created + interval '14 days')
    );

create index bible_chapters_source on bible_chapters (source_id) where source_id is not null;
create index bible_chapters_expiry on bible_chapters (expires) where expires is not null;
