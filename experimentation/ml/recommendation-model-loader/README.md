# Recommendation model loader

The loader publishes one exact version per model to `.served-versions`. Global model pins take
precedence over the newest artifact. If multiple global pins or experiment versions are requested,
the highest selected version is served and a warning identifies the conflicting selection.

Context selection uses `recommendation.contexts.servingModels` and the current context selections.
An explicit activation request takes precedence, including a rollback to an older version. Otherwise,
pending exports are loaded one at a time for validation, followed by the active generation. Content
and personalized exports are selected together. Failed discovery preserves the last selection.

The serving scripts load one version per model. When the selection changes, they stop the serving
process before starting its replacement. This briefly interrupts serving and avoids holding old and
new generations in memory together. Model artifacts and retained local history remain available.

INFO logs report context model names and selected versions. Global pins and experiment selections
are logged separately at DEBUG level; an empty global selection does not mean no context models exist.
Empty repositories without a selected version are also logged at DEBUG level. Missing selected versions
are reported at WARNING level, including when an entire selected model repository is absent.

Run loader tests with `uv run pytest`. In a composition workspace, these also exercise the real
serving scripts with a small test process to verify replacement ordering and rollback.
