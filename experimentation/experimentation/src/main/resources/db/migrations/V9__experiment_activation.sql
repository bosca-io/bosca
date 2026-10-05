alter table experimentation.experiments
    add column activation_filter jsonb;

alter table experimentation.conversion_goals
    add column page_path_prefixes varchar[] not null default '{}';

alter table experimentation.experiment_results
    add column assignments bigint;

update experimentation.experiment_results
set assignments = impressions;

alter table experimentation.experiment_results
    alter column assignments set default 0,
    alter column assignments set not null;
