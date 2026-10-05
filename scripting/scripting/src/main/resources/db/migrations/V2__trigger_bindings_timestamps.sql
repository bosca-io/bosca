alter table scripting.trigger_bindings
    add column created  timestamp with time zone not null default now(),
    add column modified timestamp with time zone not null default now();
