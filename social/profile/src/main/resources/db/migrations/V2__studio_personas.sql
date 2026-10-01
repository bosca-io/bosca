create table profiles.studio_personas
(
    id            uuid                     not null default gen_random_uuid() primary key,
    name          varchar                  not null,
    description   varchar,
    subsystem_ids varchar[]                not null,
    enabled       boolean                  not null default true,
    created       timestamp with time zone not null default now(),
    modified      timestamp with time zone not null default now()
);

create table profiles.studio_persona_profiles
(
    persona_id uuid not null,
    profile_id uuid not null,
    created    timestamp with time zone not null default now(),
    primary key (persona_id, profile_id),
    foreign key (persona_id) references profiles.studio_personas (id) on delete cascade,
    foreign key (profile_id) references public.profiles (id) on delete cascade
);

create index idx_studio_persona_profiles_profile on profiles.studio_persona_profiles (profile_id);
