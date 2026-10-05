create table bibles
(
    metadata_id        uuid    not null,
    version            int     not null,
    system_id          varchar not null,
    name               varchar not null,
    name_local         varchar not null,
    description        varchar not null,
    abbreviation       varchar not null,
    abbreviation_local varchar not null,
    styles             jsonb,
    primary key (metadata_id, version),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

create table bible_languages
(
    metadata_id      uuid    not null,
    version          int     not null,
    iso              varchar not null,
    name             varchar not null,
    name_local       varchar not null,
    script           varchar not null,
    script_code      varchar not null,
    script_direction varchar not null,
    sort             int     not null,
    primary key (metadata_id, version, iso),
    foreign key (metadata_id, version) references bibles (metadata_id, version) on delete cascade
);

create table bible_books
(
    metadata_id  uuid    not null,
    version      int     not null,
    usfm         varchar not null,
    name_short   varchar not null,
    name_long    varchar not null,
    abbreviation varchar not null,
    sort         int     not null,
    primary key (metadata_id, version, usfm),
    foreign key (metadata_id, version) references bibles (metadata_id, version) on delete cascade
);

create table bible_chapters
(
    metadata_id uuid    not null,
    version     int     not null,
    book_usfm   varchar not null,
    usfm        varchar not null,
    components  jsonb   not null,
    sort        int     not null,
    primary key (metadata_id, version, book_usfm, usfm),
    foreign key (metadata_id, version, book_usfm) references bible_books (metadata_id, version, usfm) on delete cascade
);