alter table communications.bml_email_projects
    rename to bml_message_projects;

alter table communications.delivery_status
    rename column email_template to bml_template;
