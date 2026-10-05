-- Release orchestration triggers (GIT-SPEC-6): a pipeline can be started by the release lifecycle —
-- `on: release` when a release starts, `on: promotion` when a release is promoted to an environment.
-- Runs carry the release identity as parameters (release.id/version/name, promotion.environment).
alter type git.pipeline_trigger_type add value if not exists 'release';
alter type git.pipeline_trigger_type add value if not exists 'promotion';
