package bosca.workops.migration

import bosca.db.ConnectionPool
import java.sql.PreparedStatement

/**
 * Seeds global types and tables that are prerequisites for the Work Ops
 * schema but are normally managed by the core/auth modules. Required for
 * isolated smoke/integration tests that boot a fresh database.
 */
suspend fun seedPrerequisites(pool: ConnectionPool) {
    pool.connection().useStatement(
        """
        do $$
        begin
            if not exists (select 1 from pg_type where typname = 'permission_action') then
                create type permission_action as enum (
                    'view', 'list', 'edit', 'manage', 'delete', 'execute', 'impersonate'
                );
            end if;
        end $$;
        create table if not exists public.groups (
            id uuid not null primary key default gen_random_uuid(),
            name varchar not null unique,
            description varchar not null default ''
        );
        """.trimIndent()
    ) { stmt: PreparedStatement ->
        stmt.execute()
    }
}
