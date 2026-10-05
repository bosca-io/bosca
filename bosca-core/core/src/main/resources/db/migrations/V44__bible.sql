drop table bible_chapters;
drop table bible_books;
drop table bible_languages;
drop table bibles;

create table bibles
(
    metadata_id        uuid    not null,
    version            int     not null,
    variant            varchar not null,
    default_variant    boolean not null default false,
    system_id          varchar not null,
    name               varchar not null,
    name_local         varchar not null,
    description        varchar not null,
    abbreviation       varchar not null,
    abbreviation_local varchar not null,
    styles             jsonb,
    primary key (metadata_id, version, variant),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

create table bible_languages
(
    metadata_id      uuid    not null,
    version          int     not null,
    variant          varchar not null,
    iso              varchar not null,
    name             varchar not null,
    name_local       varchar not null,
    script           varchar not null,
    script_code      varchar not null,
    script_direction varchar not null,
    sort             int     not null,
    primary key (metadata_id, version, variant, iso),
    foreign key (metadata_id, version, variant) references bibles (metadata_id, version, variant) on delete cascade
);

create table bible_books
(
    metadata_id  uuid    not null,
    version      int     not null,
    variant      varchar not null,
    usfm         varchar not null,
    name_short   varchar not null,
    name_long    varchar not null,
    abbreviation varchar not null,
    sort         int     not null,
    primary key (metadata_id, version, variant, usfm),
    foreign key (metadata_id, version, variant) references bibles (metadata_id, version, variant) on delete cascade
);

create table bible_chapters
(
    metadata_id uuid    not null,
    version     int     not null,
    variant     varchar not null,
    book_usfm   varchar not null,
    usfm        varchar not null,
    components  jsonb   not null,
    sort        int     not null,
    primary key (metadata_id, version, variant, book_usfm, usfm),
    foreign key (metadata_id, version, variant, book_usfm) references bible_books (metadata_id, version, variant, usfm) on delete cascade
);