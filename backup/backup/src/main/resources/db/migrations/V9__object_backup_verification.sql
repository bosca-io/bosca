alter table backup.object_progress
    add column verified_version text,
    add column verified timestamptz,
    add constraint object_progress_verification check (
        (verified_version is null and verified is null)
        or (
            status = 'completed' and verified_version is not null
            and btrim(verified_version) <> '' and verified_version <> 'null'
            and verified is not null
        )
    );
