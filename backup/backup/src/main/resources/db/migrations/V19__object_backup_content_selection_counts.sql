alter table backup.object_capture_plans
    add column content_selection_counts jsonb
        check (content_selection_counts is null or jsonb_typeof(content_selection_counts) = 'object');
