create table languages
(
    tag        varchar not null,
    name       varchar not null,
    localName  varchar not null,
    attributes json,
    primary key (tag)
);