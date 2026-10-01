create type ai.mcp_transport_type as enum ('SSE', 'STDIO', 'STREAMABLE_HTTP');

create table ai.mcp_server_registrations
(
    id             uuid                  not null default gen_random_uuid(),
    key            varchar               not null unique,
    name           varchar               not null,
    description    varchar               not null default '',
    transport_type ai.mcp_transport_type not null,
    configuration  jsonb                 not null,
    enabled        boolean               not null default true,
    primary key (id)
);
