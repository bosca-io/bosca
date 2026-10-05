DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT con.conname INTO constraint_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE nsp.nspname = 'artifacts'
      AND rel.relname = 'repositories'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) LIKE '%type%';

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE artifacts.repositories DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE artifacts.repositories
    ADD CONSTRAINT repositories_type_check
    CHECK (type IN ('docker', 'helm', 'maven', 'npm', 'raw'));
