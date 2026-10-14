ALTER TABLE experimentation.conversion_goals
    ADD COLUMN activation JSONB,
    ADD COLUMN outcome_scope JSONB;
ALTER TABLE experimentation.experiment_results ADD COLUMN goal_analysis JSONB;
