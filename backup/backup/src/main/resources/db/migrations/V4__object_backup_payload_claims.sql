lock table backup.object_progress in share row exclusive mode;
lock table backup.object_runs in share row exclusive mode;

do $$
declare
    conflicting record;
begin
    select destination_id, payload->>'payloadPath' as path
    into conflicting
    from backup.object_progress
    where operation = 'backup'
    group by destination_id, payload->>'payloadPath'
    having count(distinct jsonb_build_array(payload->'length', payload->'sha256')) > 1
    order by destination_id, payload->>'payloadPath'
    limit 1;

    if found then
        raise exception 'Conflicting retained backup payload: %/%', conflicting.destination_id, conflicting.path;
    end if;

    select r.destination_id, r.path
    into conflicting
    from backup.object_runs r
    where r.operation = 'backup' and exists (
        select 1 from backup.object_progress p
        where p.operation = 'backup' and p.destination_id = r.destination_id
            and p.payload->>'payloadPath' = r.path
    )
    order by r.destination_id, r.path
    limit 1;

    if found then
        raise exception 'Conflicting retained backup path: %/%', conflicting.destination_id, conflicting.path;
    end if;

    select destination_id, path
    into conflicting
    from backup.object_runs
    where operation = 'backup'
    group by destination_id, path
    having count(distinct key) > 1
    order by destination_id, path
    limit 1;

    if found then
        raise exception 'Conflicting retained backup path: %/%', conflicting.destination_id, conflicting.path;
    end if;
end $$;

create table backup.object_paths (
    destination_id text not null,
    path text not null,
    length bigint check (length >= 0),
    sha256 text check (sha256 ~ '^[0-9a-f]{64}$'),
    manifest_key varchar(64),
    primary key (destination_id, path),
    unique (destination_id, path, length, sha256),
    unique (destination_id, path, manifest_key),
    check (
        (length is not null and sha256 is not null and manifest_key is null) or
        (length is null and sha256 is null and manifest_key is not null)
    )
);

insert into backup.object_paths (destination_id, path, length, sha256)
select distinct destination_id, payload->>'payloadPath', (payload->>'length')::bigint, payload->>'sha256'
from backup.object_progress
where operation = 'backup';

insert into backup.object_paths (destination_id, path, manifest_key)
select destination_id, path, key from backup.object_runs where operation = 'backup';

alter table backup.object_progress
    add column retained_path text generated always as (
        case when operation = 'backup' then payload->>'payloadPath' end
    ) stored,
    add column retained_length bigint generated always as (
        case when operation = 'backup' then (payload->>'length')::bigint end
    ) stored,
    add column retained_sha256 text generated always as (
        case when operation = 'backup' then payload->>'sha256' end
    ) stored,
    add constraint object_progress_retained_payload_required check (
        operation <> 'backup' or
            (retained_path is not null and retained_length is not null and retained_sha256 is not null)
    ),
    add constraint object_progress_retained_payload foreign key
        (destination_id, retained_path, retained_length, retained_sha256)
        references backup.object_paths (destination_id, path, length, sha256);

alter table backup.object_runs
    add column retained_manifest_path text generated always as (
        case when operation = 'backup' then path end
    ) stored,
    add column retained_manifest_key varchar(64) generated always as (
        case when operation = 'backup' then key end
    ) stored,
    add constraint object_runs_retained_manifest foreign key
        (destination_id, retained_manifest_path, retained_manifest_key)
        references backup.object_paths (destination_id, path, manifest_key);
