delete from public.profile_relationships
where profile_id_1 = profile_id_2;

alter table public.profile_relationships
    add constraint profile_relationship_distinct_profiles
        check (profile_id_1 <> profile_id_2);
