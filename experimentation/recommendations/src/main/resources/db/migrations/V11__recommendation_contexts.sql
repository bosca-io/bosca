create table recommendations.contexts (
    id              uuid primary key default gen_random_uuid(),
    type            text not null unique,
    name            text not null,
    description     text not null default '',
    content_filter  jsonb not null,
    created         timestamptz not null default now(),
    modified        timestamptz not null default now()
);

insert into recommendations.contexts (type, name, description, content_filter)
values (
    'default',
    'Default',
    'Default recommendations for sites and applications. Excludes raw assets that should normally be represented by their containing content.',
    '{"includedContentTypePrefixes":[],"excludedContentTypePrefixes":["image/","video/","audio/","font/","model/","application/octet-stream"],"includeCollections":true}'::jsonb
);
