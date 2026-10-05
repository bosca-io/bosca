create index if not exists idx_profile_guide_progress_metadata_modified
    on public.profile_guide_progress (metadata_id, modified desc);

create index if not exists idx_profile_guide_history_metadata_completed
    on public.profile_guide_history (metadata_id, completed desc);
