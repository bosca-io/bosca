-- Speed up the aggregation job's per-goal event queries which filter
-- conversion_goals by experiment_id and event_type. The existing
-- idx_conversion_goals_experiment only covers experiment_id; adding
-- event_type avoids a sequential scan when the experiment has many
-- goals with different event types.
CREATE INDEX IF NOT EXISTS idx_conversion_goals_experiment_event
    ON experimentation.conversion_goals (experiment_id, event_type);
