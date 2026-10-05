-- Failure summary captured by the agent from the tail of the step's log
-- output, so the UI can show why a step failed without the user digging
-- through the full log file in object storage.
alter table git.pipeline_steps
    add column error_message varchar;
