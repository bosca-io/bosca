drop trigger guide_progress_history on profile_guide_history;
drop function guide_history_trigger;

alter table profile_guide_progress drop column completed;
alter table profile_guide_progress
    add column completed_step_ids bigint[] not null default '{}';

drop table profile_guide_progress_steps;