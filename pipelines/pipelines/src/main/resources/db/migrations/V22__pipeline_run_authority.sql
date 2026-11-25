alter table pipelines.pipeline_run add column authority jsonb;

-- Existing runs keep the authority they executed under: triggered, scheduled and anonymous runs
-- under the pipelines service account, and on-demand runs as their caller with that caller's
-- current groups and no API-token scope restriction.
update pipelines.pipeline_run
set authority = '{"system":true}'::jsonb
where principal_id is null;

update pipelines.pipeline_run r
set authority = jsonb_build_object(
    'system', false,
    'groupIds', coalesce(
        (select jsonb_agg(pg.group_id order by pg.group_id) from public.principal_groups pg where pg.principal = r.principal_id),
        '[]'::jsonb
    )
)
where r.principal_id is not null;
