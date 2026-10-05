create table traits
(
    id          varchar not null,
    name        varchar not null,
    description varchar not null,
    delete_workflow_id varchar,
    primary key (id)
);

create table trait_workflows
(
    trait_id    varchar not null,
    workflow_id varchar not null,
    primary key (trait_id, workflow_id),
    foreign key (trait_id) references traits (id)
);

create table categories
(
    id   uuid    not null default gen_random_uuid(),
    name varchar not null,
    primary key (id)
);

create type collection_type as enum ('root', 'standard', 'folder', 'queue');

create table collections
(
    id                        uuid      not null       default gen_random_uuid(),
    name                      varchar   not null,
    description               varchar,
    type                      collection_type          default 'standard',
    attributes                jsonb     not null       default '{}',
    system_attributes         jsonb,
    labels                    varchar[] not null       default '{}',
    created                   timestamp with time zone default now(),
    modified                  timestamp with time zone default now(),
    enabled                   boolean                  default true,
    workflow_state_id         varchar   not null       default 'pending',
    workflow_state_pending_id varchar,
    public                    boolean   not null       default false,
    public_list               boolean   not null       default false,
    permission_mutation       int       not null       default 0,
    delete_workflow_id        varchar,
    ordering                  jsonb,
    ready                     timestamp with time zone,
    primary key (id)
);

create table collection_traits
(
    collection_id uuid,
    trait_id      varchar,
    primary key (collection_id, trait_id),
    foreign key (collection_id) references collections (id) on delete cascade,
    foreign key (trait_id) references traits (id) on delete cascade
);

create table collection_categories
(
    collection_id uuid,
    category_id   uuid,
    primary key (collection_id, category_id),
    foreign key (collection_id) references collections (id) on delete cascade,
    foreign key (category_id) references categories (id) on delete cascade
);

create table sources
(
    id            uuid    not null default gen_random_uuid(),
    name          varchar not null,
    description   varchar not null,
    configuration jsonb   not null default '{}'::jsonb,
    primary key (id)
);

insert into sources (name, description)
values ('uploader', 'metadata from an upload using the uploader'),
       ('workflow', 'metadata generated during a workflow');

create type metadata_type as enum ('standard', 'variant');

create table metadata
(
    id                        uuid      not null                                  default gen_random_uuid(),
    version                   int       not null                                  default 1,
    active_version            int       not null                                  default 1,
    parent_id                 uuid,
    name                      varchar   not null check (length(name) > 0),
    type                      metadata_type                                       default 'standard',
    content_type              varchar   not null check (length(content_type) > 0),
    content_length            bigint,
    language_tag              varchar   not null check (length(language_tag) > 0) default 'en',
    labels                    varchar[] not null                                  default '{}',
    attributes                jsonb     not null                                  default '{}',
    system_attributes         jsonb,
    created                   timestamp with time zone                            default now(),
    modified                  timestamp with time zone                            default now(),
    workflow_state_id         varchar   not null                                  default 'pending',
    workflow_state_pending_id varchar,
    source_id                 uuid,
    source_identifier         varchar,
    delete_workflow_id        varchar,
    public                    boolean   not null                                  default false,
    public_content            boolean   not null                                  default false,
    public_supplementary      boolean   not null                                  default false,
    permission_mutation       int       not null                                  default 0,
    uploaded                  timestamp with time zone,
    ready                     timestamp with time zone,
    primary key (id),
    foreign key (parent_id) references metadata (id) on delete cascade,
    foreign key (source_id) references sources (id)
);

create table collection_items
(
    id                  bigserial,
    collection_id       uuid,
    child_collection_id uuid,
    child_metadata_id   uuid,
    primary key (id),
    foreign key (collection_id) references collections (id) on delete cascade,
    foreign key (child_collection_id) references collections (id) on delete cascade,
    foreign key (child_metadata_id) references metadata (id) on delete cascade,
    unique (collection_id, child_collection_id),
    unique (collection_id, child_metadata_id)
);

create table metadata_supplementary
(
    metadata_id       uuid                     not null,
    key               varchar                  not null,
    name              varchar                  not null,
    content_type      varchar                  not null,
    content_length    bigint,
    created           timestamp with time zone not null default now(),
    modified          timestamp with time zone not null default now(),
    source_id         uuid,
    source_identifier varchar,
    uploaded          timestamp with time zone,
    primary key (metadata_id, key),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (source_id) references sources (id),
    unique (metadata_id, key)
);

create table metadata_supplementary_traits
(
    metadata_id uuid    not null,
    key         varchar not null,
    trait_id    varchar not null,
    primary key (metadata_id, key, trait_id),
    foreign key (metadata_id, key) references metadata_supplementary (metadata_id, key) on delete cascade,
    foreign key (trait_id) references traits (id) on delete cascade
);

create table metadata_versions
(
    id                        uuid      not null,
    version                   int       not null,
    parent_id                 uuid,
    name                      varchar   not null check (length(name) > 0),
    type                      metadata_type                                       default 'standard',
    content_type              varchar   not null check (length(content_type) > 0),
    content_length            bigint,
    language_tag              varchar   not null check (length(language_tag) > 0) default 'en',
    labels                    varchar[] not null                                  default '{}',
    attributes                jsonb     not null                                  default '{}',
    system_attributes         jsonb,
    created                   timestamp with time zone                            default now(),
    modified                  timestamp with time zone                            default now(),
    workflow_state_id         varchar   not null                                  default 'pending',
    workflow_state_pending_id varchar,
    metadata                  jsonb,
    source_id                 uuid,
    source_identifier         varchar,
    delete_workflow_id        varchar,
    primary key (id, version),
    foreign key (id) references metadata (id) on delete cascade,
    foreign key (parent_id) references metadata (id) on delete cascade,
    foreign key (source_id) references sources (id)
);

create table metadata_versions_supplementary
(
    metadata_id       uuid                     not null,
    version           int                      not null,
    key               varchar                  not null,
    name              varchar                  not null,
    content_type      varchar                  not null,
    content_length    bigint,
    created           timestamp with time zone not null default now(),
    modified          timestamp with time zone not null default now(),
    source_id         uuid,
    source_identifier varchar,
    uploaded          timestamp with time zone,
    primary key (metadata_id, version, key),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (source_id) references sources (id),
    unique (metadata_id, key)
);

create table metadata_version_supplementary_traits
(
    metadata_id uuid    not null,
    version     int     not null,
    key         varchar not null,
    trait_id    varchar not null,
    primary key (metadata_id, version, key, trait_id),
    foreign key (metadata_id, key) references metadata_supplementary (metadata_id, key) on delete cascade,
    foreign key (trait_id) references traits (id) on delete cascade
);

create table metadata_version_traits
(
    metadata_id uuid,
    version     int,
    trait_id    varchar,
    primary key (metadata_id, version, trait_id),
    foreign key (metadata_id, version) references metadata_versions (id, version) on delete cascade,
    foreign key (trait_id) references traits (id) on delete cascade
);

create table metadata_version_categories
(
    metadata_id uuid,
    version     int,
    category_id uuid,
    primary key (metadata_id, version, category_id),
    foreign key (metadata_id, version) references metadata_versions (id, version) on delete cascade,
    foreign key (category_id) references categories (id) on delete cascade
);

create or replace function metadata_versions_trigger() returns trigger AS
$version_trigger$
begin
    if
        new.version = old.version then
        return new;
    end if;

    insert into metadata_versions (id, version, parent_id, name, content_type, content_length,
                                   attributes, system_attributes,
                                   workflow_state_pending_id, source_id, source_identifier,
                                   delete_workflow_id)
    values (old.id, old.version, old.parent_id, old.name, old.content_type, old.content_length,
            old.attributes, old.system_attributes,
            old.workflow_state_pending_id, old.source_id, old.source_identifier,
            old.delete_workflow_id);

    insert into metadata_version_traits (metadata_id, version, trait_id)
    select metadata_id, new.version, trait_id
    from metadata_traits
    where metadata_id = new.id;

    delete
    from metadata_traits
    where metadata_id = new.id;

    insert into metadata_version_categories (metadata_id, version, category_id)
    select metadata_id, new.version, category_id
    from metadata_categories
    where metadata_id = new.id;

    delete
    from metadata_categories
    where metadata_id = new.id;

    insert into metadata_versions_supplementary (metadata_id, version, key, name, content_type, content_length, created,
                                                 modified, source_id, source_identifier, uploaded)
    select metadata_id,
           old.version,
           key,
           name,
           content_type,
           content_length,
           created,
           modified,
           source_id,
           source_identifier,
           uploaded
    from metadata_supplementary
    where metadata_id = new.id;

    insert into metadata_version_supplementary_traits (metadata_id, version, key, trait_id)
    select metadata_id, old.version, key, trait_id
    from metadata_supplementary_traits
    where metadata_id = new.id;

    delete
    from metadata_supplementary
    where metadata_id = new.id;

    return new;
end;
$version_trigger$
    language plpgsql;


create trigger metadata_versions
    before update
    on metadata
    for each row
execute function metadata_versions_trigger();

create table metadata_workflow_transition_history
(
    id            bigserial not null,
    metadata_id   uuid      not null,
    to_state_id   varchar   not null,
    from_state_id varchar   not null,
    principal     uuid      not null,
    status        varchar,
    success       boolean   not null       default false,
    complete      boolean   not null       default false,
    created       timestamp with time zone default now(),
    primary key (id),
    foreign key (metadata_id) references metadata (id) on delete cascade
);

create table collection_workflow_transition_history
(
    id            bigserial not null,
    collection_id uuid      not null,
    to_state_id   varchar   not null,
    from_state_id varchar   not null,
    principal     uuid      not null,
    status        varchar,
    success       boolean   not null       default false,
    complete      boolean   not null       default false,
    created       timestamp with time zone default now(),
    primary key (id),
    foreign key (collection_id) references collections (id) on delete cascade
);

create table metadata_relationships
(
    metadata1_id uuid,
    metadata2_id uuid,
    relationship varchar,
    attributes   jsonb,
    primary key (metadata1_id, metadata2_id, relationship),
    foreign key (metadata1_id) references metadata (id) on delete cascade,
    foreign key (metadata2_id) references metadata (id) on delete cascade
);

create table metadata_traits
(
    metadata_id uuid,
    trait_id    varchar,
    primary key (metadata_id, trait_id),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (trait_id) references traits (id) on delete cascade
);

create table metadata_categories
(
    metadata_id uuid,
    category_id uuid,
    primary key (metadata_id, category_id),
    foreign key (metadata_id) references metadata (id) on delete cascade,
    foreign key (category_id) references categories (id) on delete cascade
);

create table models
(
    id            uuid    not null default gen_random_uuid(),
    type          varchar not null,
    name          varchar not null,
    description   varchar not null,
    configuration jsonb   not null,
    primary key (id)
);

create table prompts
(
    id            uuid    not null default gen_random_uuid(),
    name          varchar not null,
    description   varchar not null,
    system_prompt text    not null,
    user_prompt   text    not null,
    input_type    varchar,
    output_type   varchar,
    primary key (id)
);

create type storage_system_type as enum ('vector', 'search', 'supplementary');

create table storage_systems
(
    id            uuid                not null default gen_random_uuid(),
    name          varchar             not null,
    description   varchar             not null,
    type          storage_system_type not null,
    configuration jsonb               not null,
    primary key (id)
);

insert into storage_systems (name, description, type, configuration) values ('Default Search', 'Default Search', 'search', '{"type": "meilisearch", "indexName": "default", "primaryKey": "_id", "embeddings.url": "http://ollama:11434/api/embeddings", "embeddings.model": "all-minilm", "embeddings.source": "ollama", "embeddings.template": "A document titled {{doc.name}} with a body of {{doc._content|truncatewords:500}}"}'::jsonb);

create table storage_system_models
(
    system_id     uuid  not null,
    model_id      uuid  not null,
    configuration jsonb not null default '{
      "type": "default"
    }'::jsonb,
    primary key (system_id, model_id),
    foreign key (system_id) references storage_systems (id),
    foreign key (model_id) references models (id)
);

create table activities
(
    id                varchar not null,
    name              varchar not null,
    description       varchar not null,
    child_workflow_id varchar,
    configuration     jsonb   not null default '{}',
    primary key (id)
);

create type activity_parameter_type as enum ('context', 'supplementary', 'supplementary_array');

create table activity_inputs
(
    activity_id varchar                 not null,
    name        varchar                 not null,
    type        activity_parameter_type not null,
    primary key (activity_id, name),
    foreign key (activity_id) references activities (id) on delete cascade
);

create table activity_outputs
(
    activity_id varchar                 not null,
    name        varchar                 not null,
    type        activity_parameter_type not null,
    primary key (activity_id, name),
    foreign key (activity_id) references activities (id) on delete cascade
);

create table workflows
(
    id            varchar not null, -- This is the identifier of the temporal workflow
    name          varchar not null,
    description   varchar not null,
    queue         varchar not null check (length(queue) > 0),
    configuration jsonb   not null default '{}',
    primary key (id)
);

alter table metadata
    add foreign key (delete_workflow_id) references workflows (id);
alter table traits
    add foreign key (delete_workflow_id) references workflows (id);
alter table trait_workflows
    add foreign key (workflow_id) references workflows (id);
alter table activities
    add foreign key (child_workflow_id) references workflows (id);

create table workflow_activities
(
    id              bigserial not null,
    workflow_id     varchar   not null,
    activity_id     varchar   not null,
    queue           varchar,
    execution_group int       not null,
    configuration   jsonb     not null default '{}',
    primary key (id),
    foreign key (workflow_id) references workflows (id),
    foreign key (activity_id) references activities (id)
);

create table workflow_activity_inputs
(
    activity_id bigint  not null,
    name        varchar not null,
    value       varchar not null,
    primary key (activity_id, name),
    foreign key (activity_id) references workflow_activities
);

create table workflow_activity_outputs
(
    activity_id bigint  not null,
    name        varchar not null,
    value       varchar not null,
    primary key (activity_id, name),
    foreign key (activity_id) references workflow_activities
);

create index ix_workflow_activities_ix on workflow_activities (workflow_id);

create table workflow_activity_storage_systems
(
    activity_id       bigint not null,
    storage_system_id uuid   not null,
    configuration     jsonb  not null default '{}'::jsonb,
    primary key (activity_id, storage_system_id),
    foreign key (activity_id) references workflow_activities (id),
    foreign key (storage_system_id) references storage_systems (id)
);

create table workflow_activity_models
(
    activity_id   bigint not null,
    model_id      uuid   not null,
    configuration jsonb  not null default '{}'::jsonb,
    primary key (activity_id, model_id),
    foreign key (activity_id) references workflow_activities (id),
    foreign key (model_id) references models (id)
);

create table workflow_activity_prompts
(
    activity_id   bigint not null,
    prompt_id     uuid   not null,
    configuration jsonb  not null default '{}'::jsonb,
    primary key (activity_id, prompt_id),
    foreign key (activity_id) references workflow_activities (id),
    foreign key (prompt_id) references prompts (id)
);

create type workflow_state_type as enum ('processing', 'draft', 'pending', 'approval', 'approved', 'published', 'failure');

create table workflow_states
(
    id                varchar             not null,
    name              varchar             not null,
    description       varchar             not null,
    type              workflow_state_type not null,
    configuration     jsonb               not null default '{}',
    workflow_id       varchar,
    exit_workflow_id  varchar, -- workflow that must return true before exiting
    entry_workflow_id varchar, -- workflow that must return true before entering
    primary key (id),
    foreign key (workflow_id) references workflows (id),
    foreign key (exit_workflow_id) references workflows (id)
);

alter table collections
    add foreign key (workflow_state_id) references workflow_states (id);
alter table collections
    add foreign key (workflow_state_pending_id) references workflow_states (id);
alter table metadata
    add foreign key (workflow_state_id) references workflow_states (id);
alter table metadata
    add foreign key (workflow_state_pending_id) references workflow_states (id);

create table workflow_state_transitions
(
    from_state_id varchar not null,
    to_state_id   varchar not null,
    description   varchar not null,
    primary key (from_state_id, to_state_id),
    foreign key (from_state_id) references workflow_states (id),
    foreign key (to_state_id) references workflow_states (id)
);