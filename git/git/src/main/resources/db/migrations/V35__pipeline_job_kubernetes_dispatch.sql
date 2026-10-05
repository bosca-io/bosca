alter table git.pipeline_jobs
    add column kubernetes_dispatch_id uuid,
    add column kubernetes_finalized_at timestamptz;

alter table git.pipeline_agents
    add column api_token_credential_id bigint,
    add column token_principal_id uuid;

-- Existing agents already use API tokens; connect their stored raw-token hash to the
-- corresponding credential so credential-bound authorization remains upgrade compatible.
update git.pipeline_agents agent
set api_token_credential_id = credential.id,
    token_principal_id = credential.principal
from principal_credentials credential
where credential.type = 'api_token'
  and credential.attributes ->> 'identifier' = 'sha256:' || agent.token_hash;

alter table git.pipeline_agents
    add constraint fk_pipeline_agents_api_token
        foreign key (api_token_credential_id)
        references principal_credentials (id)
        on delete set null,
    add constraint fk_pipeline_agents_token_principal
        foreign key (token_principal_id)
        references principals (id)
        on delete cascade;

create unique index idx_git_pipeline_agents_api_token_credential
    on git.pipeline_agents (api_token_credential_id)
    where api_token_credential_id is not null;

create index idx_git_pipeline_jobs_kubernetes_dispatch
    on git.pipeline_jobs (runner_label, created)
    where status = 'queued' and kubernetes_dispatch_id is null;

create index idx_git_pipeline_jobs_kubernetes_reconcile
    on git.pipeline_jobs (created)
    where kubernetes_dispatch_id is not null and kubernetes_finalized_at is null;
