alter table metadata
    add column recommendation_contexts text[] not null default '{}';

alter table collections
    add column recommendation_contexts text[] not null default '{}';

create index metadata_recommendation_contexts_idx
    on metadata using gin (recommendation_contexts);

create index collections_recommendation_contexts_idx
    on collections using gin (recommendation_contexts);
