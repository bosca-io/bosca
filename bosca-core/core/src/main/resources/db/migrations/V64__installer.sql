create table package_installations
(
    id      uuid                     not null default gen_random_uuid(),
    key     varchar                  not null,
    version varchar                  not null,
    created timestamp with time zone not null,
    primary key (id)
)