-- Private Helm repository credentials follow the same separate encrypted-row
-- pattern as cluster kubeconfigs. id is the per-repository encryption context;
-- nonce and data are produced by EncryptionService. Public repositories have
-- no row in this table.

create table kubernetes.helm_repo_credential (
    repo_name   text primary key references kubernetes.helm_repo (name) on delete cascade,
    id          uuid        not null,
    nonce       bytea       not null,
    data        bytea       not null,
    created_at  timestamptz not null default now(),
    modified_at timestamptz not null default now()
);
