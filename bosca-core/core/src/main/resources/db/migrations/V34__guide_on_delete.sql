alter table guide_template_step_modules
    drop constraint guide_template_step_modules_template_metadata_id_fkey;

alter table guide_template_step_modules
    add foreign key (template_metadata_id) references metadata
        on delete cascade;

alter table guide_step_modules
    drop constraint guide_step_modules_metadata_id_fkey;

alter table guide_step_modules
    add foreign key (metadata_id) references metadata
        on delete cascade;