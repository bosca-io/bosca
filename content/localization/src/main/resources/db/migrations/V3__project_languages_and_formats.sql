create table localization.project_languages
(
    project_id   uuid        not null references localization.projects (id) on delete cascade,
    language_tag varchar     not null references public.languages (tag),
    created      timestamp with time zone not null default now(),
    primary key (project_id, language_tag)
);

create table localization.project_formats
(
    project_id uuid        not null references localization.projects (id) on delete cascade,
    format     varchar     not null,
    created    timestamp with time zone not null default now(),
    primary key (project_id, format)
);
