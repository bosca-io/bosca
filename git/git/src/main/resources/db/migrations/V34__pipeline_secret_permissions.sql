-- GIT-SPEC-6: secrets become permissible entities with an optional environment scope, and jobs
-- carry their declared secret names.
--
-- A secret's use is evaluated against the run's INITIATING principal: explicit group grants on the
-- secret itself (this table), falling back to the owning repository's permissions when the secret
-- has no grants of its own. `environment_key` scopes a secret to jobs bound to that environment —
-- production store credentials scoped to `production` cannot be pulled into an arbitrary branch
-- pipeline that names them.

alter table git.pipeline_secrets
    add column if not exists environment_key varchar;

create table if not exists git.pipeline_secret_permissions (
    secret_id  uuid              not null references git.pipeline_secrets(id) on delete cascade,
    group_id   uuid              not null references groups(id) on delete cascade,
    action     permission_action not null,
    primary key (secret_id, group_id, action)
);

-- The pipeline definition's `secrets:` declaration, flattened onto each job at run creation —
-- resolution reads it server-side, so a job can only obtain what its pipeline declared.
alter table git.pipeline_jobs
    add column if not exists secrets varchar[] not null default '{}';
