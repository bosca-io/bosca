-- Link each requirement to an auto-created task that manages the
-- requirement's lifecycle state.

alter table workops.requirement
    add column task_id uuid references workops.task(id) on delete set null;

create index requirement_task_idx on workops.requirement(task_id) where task_id is not null;
