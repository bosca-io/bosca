-- Fixture for FlywayMigrationDependencyTest. Runs in whichever schema the test
-- migration targets; the table's presence proves the chain was applied.
CREATE TABLE marker (
    id INTEGER PRIMARY KEY
);
