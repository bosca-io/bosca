create table bible_fums_tokens (
    metadata_id uuid not null,
    version integer not null,
    variant varchar not null,
    usfm varchar not null,
    source_id uuid not null,
    audio boolean not null,
    token varchar not null,
    created timestamptz not null,
    expires timestamptz not null,
    primary key (metadata_id, version, variant, usfm, source_id),
    check (expires > created and expires <= created + interval '336 hours')
);

create index bible_fums_tokens_expiry on bible_fums_tokens (expires);

insert into bible_fums_tokens (metadata_id, version, variant, usfm, source_id, audio, token, created, expires)
select metadata_id, version, variant, usfm, source_id, false, fums_token, created, expires
from bible_chapters
where fums_token is not null and source_id is not null and expires > now();

insert into bible_fums_tokens (metadata_id, version, variant, usfm, source_id, audio, token, created, expires)
select distinct on (chapter.metadata_id, chapter.version, chapter.variant, chapter.usfm, (recording.value->>'sourceId')::uuid)
    chapter.metadata_id, chapter.version, chapter.variant, chapter.usfm, (recording.value->>'sourceId')::uuid, true,
    recording.value->>'fumsToken', (recording.value->>'created')::timestamptz, (recording.value->>'expires')::timestamptz
from bible_chapters chapter
cross join lateral jsonb_array_elements(chapter.audio) recording
where chapter.audio is not null and recording.value->>'fumsToken' is not null
    and (recording.value->>'expires')::timestamptz > now()
order by chapter.metadata_id, chapter.version, chapter.variant, chapter.usfm,
    (recording.value->>'sourceId')::uuid, (recording.value->>'created')::timestamptz desc;

update bible_chapters set audio = (
    select jsonb_agg(case when coalesce((recording.value->>'cached')::boolean, true)
        then recording.value - 'fumsToken' - 'cached'
        else (recording.value - 'fumsToken' - 'cached') || jsonb_build_object('evicted', to_jsonb(now())) end)
    from jsonb_array_elements(audio) recording
) where audio is not null and audio <> '[]'::jsonb;

alter table bible_chapters drop column fums_token;
