-- Environment target taxonomy (WORKOPS-SPEC-22/23): an environment is a typed distribution target, not
-- just a named stage. `kind` is the lifecycle stage; `target_type` + `target_ref` name the external
-- channel it deploys to (a Play Store track, TestFlight, App Store, or a generic API/server slot);
-- `ephemeral` marks on-demand preview environments (per-branch/PR) that are created and torn down.
create type workops.environment_kind as enum ('preview', 'development', 'staging', 'production');
create type workops.environment_target_type as enum ('generic', 'play_track', 'testflight', 'app_store');

alter table workops.environment
    add column kind        workops.environment_kind        not null default 'development',
    add column target_type workops.environment_target_type not null default 'generic',
    add column target_ref  varchar,
    add column ephemeral   boolean not null default false;
