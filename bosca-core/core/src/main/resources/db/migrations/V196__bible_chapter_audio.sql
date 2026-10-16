alter table bible_chapters add column audio jsonb;
create index bible_chapters_audio on bible_chapters using gin (audio) where audio is not null;
