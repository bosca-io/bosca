alter table experimentation.experiments
    add column excluded_principal_ids uuid[] not null default '{}'::uuid[];
