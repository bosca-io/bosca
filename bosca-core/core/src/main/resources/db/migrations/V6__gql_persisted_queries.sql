create table gql_persisted_queries
(
    sha256 varchar not null,
    query  varchar not null,
    primary key (sha256)
);