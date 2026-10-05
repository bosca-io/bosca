alter table localization.translation_history
    add constraint chk_history_table_name
        check (table_name in ('translations', 'plural_translations', 'document_translations'));
