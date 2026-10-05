create type git.commit_status_state as enum ('pending', 'success', 'failure', 'error');

alter table git.commit_statuses
    alter column state type git.commit_status_state using state::git.commit_status_state;
