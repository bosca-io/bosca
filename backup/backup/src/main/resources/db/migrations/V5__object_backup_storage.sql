create table backup.object_stores (
    key text primary key check (btrim(key) <> ''),
    type text not null,
    endpoint text,
    bucket text,
    region text,
    base_path text,
    created timestamptz not null default now(),
    unique nulls not distinct (type, endpoint, bucket, base_path),
    check (
        (type = 'fs' and base_path is not null and btrim(base_path) <> ''
            and endpoint is null and bucket is null and region is null)
        or
        (type = 's3' and endpoint is not null and btrim(endpoint) <> ''
            and bucket is not null and btrim(bucket) <> ''
            and region is not null and btrim(region) <> '' and base_path is null)
    )
);
