-- Superseded before it shipped a feature: a dependency needs no "build order only" flag — whether the
-- provider must co-release is DERIVED: a BUILD dependency is satisfied by any RELEASED provider version
-- matching its constraint; only an unsatisfied one requires the provider in the release.
alter table workops.dependency_declaration drop column build_order_only;
