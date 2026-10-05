-- Link segments to their scheduled evaluation job
alter table segmentation.segments add column scheduled_job_id uuid;

create index idx_segments_scheduled_job_id on segmentation.segments (scheduled_job_id)
    where scheduled_job_id is not null;
