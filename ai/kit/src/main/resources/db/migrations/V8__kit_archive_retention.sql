alter table kit.runtime_session add column archive_failure text;

create table kit.runtime_archive (
    key text primary key,
    session_id uuid not null references kit.runtime_session(session_id) on delete cascade,
    generation bigint not null check (generation > 0),
    bytes bigint not null check (bytes > 0),
    created timestamptz,
    unique (session_id, generation)
);

-- Register possible earlier objects for one-time collection. Unmeasured objects reserve the
-- profile's upper bound until deletion; their creation time remains unknown.
insert into kit.runtime_archive (key, session_id, generation, bytes, created)
select 'kit/runtime/' || s.session_id || '/' || g || '.tar.gz', s.session_id, g,
    case when s.archive_key = 'kit/runtime/' || s.session_id || '/' || g || '.tar.gz'
        then coalesce(s.archive_size, (s.configuration->>'maxArchiveBytes')::bigint, 8589934592)
        else coalesce((s.configuration->>'maxArchiveBytes')::bigint, 8589934592) end,
    case when s.archive_key = 'kit/runtime/' || s.session_id || '/' || g || '.tar.gz' then s.archive_created end
from kit.runtime_session s cross join lateral generate_series(1, s.generation) g;
