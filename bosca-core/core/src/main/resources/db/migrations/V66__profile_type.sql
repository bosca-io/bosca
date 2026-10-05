create type profile_type as enum ('generic', 'organization');

alter table profiles add column type profile_type default 'generic'::profile_type;