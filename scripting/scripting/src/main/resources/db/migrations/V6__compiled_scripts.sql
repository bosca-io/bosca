create table scripting.compiled_scripts
(
    key         varchar                  not null,
    version     int                      not null,
    fingerprint varchar                  not null,
    compiled    bytea                    not null,
    created     timestamp with time zone not null default now(),
    primary key (key, version, fingerprint)
);
