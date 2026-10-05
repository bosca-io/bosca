drop table gql_persisted_queries;
create table gql_persisted_queries
(
    application varchar not null,
    sha256 varchar not null,
    query  varchar not null,
    primary key (application, sha256)
);