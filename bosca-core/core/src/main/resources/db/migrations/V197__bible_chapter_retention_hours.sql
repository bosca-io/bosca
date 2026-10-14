alter table bible_chapters
    drop constraint bible_chapter_retention,
    add constraint bible_chapter_retention check (
        expires is null or (source_id is not null and created is not null
            and expires > created and expires <= created + interval '336 hours')
    );
