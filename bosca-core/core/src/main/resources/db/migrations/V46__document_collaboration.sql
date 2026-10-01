create table document_collaborations
(
    metadata_id uuid                     not null,
    version     int                      not null,
    content     bytea                    not null,
    created     timestamp with time zone not null default now(),
    modified    timestamp with time zone not null default now(),
    primary key (metadata_id, version),
    foreign key (metadata_id, version) references documents (metadata_id, version)
        on delete cascade
);

