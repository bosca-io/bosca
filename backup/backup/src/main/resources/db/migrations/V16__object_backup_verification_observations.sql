alter table backup.object_progress
    drop constraint object_progress_verification,
    add column verified_info jsonb;

update backup.object_progress
set verified_info = jsonb_build_object('length', payload->'length', 'version', verified_version)
where verified_version is not null;

alter table backup.object_progress
    drop column verified_version,
    add constraint object_progress_verification check (
        (verified_info is null and verified is null)
        or (
            status = 'completed' and verified_info is not null and verified is not null
            and jsonb_typeof(verified_info) = 'object'
            and verified_info ? 'length'
            and (verified_info->>'length')::bigint = (payload->>'length')::bigint
            and (
                verified_info->>'version' is null
                or (btrim(verified_info->>'version') <> '' and verified_info->>'version' <> 'null')
            )
        )
    );
