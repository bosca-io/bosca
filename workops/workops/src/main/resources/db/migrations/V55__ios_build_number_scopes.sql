-- iOS CFBundleVersion values restart at 1 for each major/minor release line. Existing iOS rows
-- remain in a legacy scope so new allocations start cleanly without changing retry results for
-- binaries that were already assigned a durable build identity.

alter table workops.app_build_number_counter
    add column version_scope varchar;

update workops.app_build_number_counter
set version_scope = case platform when 'ANDROID' then 'global' else 'legacy' end;

alter table workops.app_build_number_counter
    alter column version_scope set not null,
    drop constraint app_build_number_counter_pkey,
    drop constraint app_build_number_counter_range,
    add primary key (platform, application_id, version_scope),
    add constraint app_build_number_counter_version_scope check (
        (platform = 'ANDROID' and version_scope = 'global') or
        (platform = 'IOS' and (version_scope = 'legacy' or version_scope ~ '^[0-9]+\.[0-9]+$'))
    ),
    add constraint app_build_number_counter_range check (
        (platform = 'ANDROID' and last_number between 0 and 2100000000) or
        (platform = 'IOS' and version_scope = 'legacy' and last_number between 0 and 99990000) or
        (platform = 'IOS' and version_scope <> 'legacy' and last_number between 0 and 9999)
    );

alter table workops.app_build_number_allocation
    add column version_scope varchar;

update workops.app_build_number_allocation
set version_scope = case platform when 'ANDROID' then 'global' else 'legacy' end;

alter table workops.app_build_number_allocation
    alter column version_scope set not null,
    drop constraint app_build_number_allocation_platform_application_id_number_key,
    drop constraint app_build_number_allocation_range,
    add constraint app_build_number_allocation_scope_number_key
        unique (platform, application_id, version_scope, number),
    add constraint app_build_number_allocation_version_scope check (
        (platform = 'ANDROID' and version_scope = 'global') or
        (platform = 'IOS' and (version_scope = 'legacy' or version_scope ~ '^[0-9]+\.[0-9]+$'))
    ),
    add constraint app_build_number_allocation_range check (
        (platform = 'ANDROID' and number between 1 and 2100000000) or
        (platform = 'IOS' and version_scope = 'legacy' and number between 1 and 99990000) or
        (platform = 'IOS' and version_scope <> 'legacy' and number between 1 and 9999)
    );
