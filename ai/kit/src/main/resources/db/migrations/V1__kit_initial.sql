create schema if not exists kit;

-- Index of agent checkpoints. The (large) AgentCheckpointData bytes live in object storage; this table
-- is the queryable index so a run's checkpoints can be LISTED and the latest found without scanning
-- storage. `session_id` is the run the checkpoint belongs to — the parent planner run (whose id IS the
-- chat session id) or a sub-agent run (a UUID). `parent_session_id` is the owning chat session, so a
-- session's parent + sub-agent runs are grouped and removable together.
create table kit.checkpoint
(
    parent_session_id uuid,
    session_id        uuid        not null,
    checkpoint_id     uuid        not null,
    version           bigint      not null,
    created_at        timestamptz not null default now(),
    primary key (session_id, checkpoint_id)
);

create index checkpoint_session_version on kit.checkpoint (session_id, version desc);
create index checkpoint_parent on kit.checkpoint (parent_session_id);
