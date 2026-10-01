package bosca.experimentation.configuration

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.db.use
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Upgrade-path coverage for experimentation schema migrations. */
class ExperimentationMigrationIntegrationTest {

    private class PreExplicitControlMigration : Migration {
        override val schema = "experimentation"
        override val resources = listOf(
            "V1__experimentation.sql",
            "V2__goal_roles_and_policy.sql",
            "V3__bayesian_and_cuped.sql",
            "V4__assignment_identity_constraint.sql",
            "V5__conversion_goal_indexes.sql",
        )
    }

    private class PreAccountExclusionsMigration : Migration {
        override val schema = "experimentation"
        override val resources = listOf(
            "V1__experimentation.sql",
            "V2__goal_roles_and_policy.sql",
            "V3__bayesian_and_cuped.sql",
            "V4__assignment_identity_constraint.sql",
            "V5__conversion_goal_indexes.sql",
            "V6__explicit_control_and_engagement_goals.sql",
            "V7__feature_flag_assignments.sql",
        )
    }

    private class PreActivationMigration : Migration {
        override val schema = "experimentation"
        override val resources = listOf(
            "V1__experimentation.sql",
            "V2__goal_roles_and_policy.sql",
            "V3__bayesian_and_cuped.sql",
            "V4__assignment_identity_constraint.sql",
            "V5__conversion_goal_indexes.sql",
            "V6__explicit_control_and_engagement_goals.sql",
            "V7__feature_flag_assignments.sql",
            "V8__experiment_excluded_principals.sql",
        )
    }

    @Test
    fun `V9 preserves existing result denominators and leaves activation disabled`() =
        withDatabase("exp_v9_activation") { pool ->
            FlywayMigration(pool).migrate(listOf(PreActivationMigration()))
            pool.execute(
                """
                insert into experimentation.feature_flags (
                    id, key, name, type, status, variations, default_variation_key
                ) values (
                    '00000000-0000-0000-0000-000000000901', 'activation-upgrade', 'Activation upgrade',
                    'boolean', 'enabled',
                    '[{"key":"control","name":"Control","description":"","value":false}]'::jsonb,
                    'control'
                )
                """.trimIndent(),
            )
            pool.execute(
                """
                insert into experimentation.experiments (
                    id, feature_flag_id, name, control_variation_key
                ) values (
                    '00000000-0000-0000-0000-000000000902',
                    '00000000-0000-0000-0000-000000000901',
                    'Existing experiment', 'control'
                )
                """.trimIndent(),
            )
            pool.execute(
                """
                insert into experimentation.conversion_goals (
                    id, experiment_id, name, event_type, metric_type
                ) values (
                    '00000000-0000-0000-0000-000000000903',
                    '00000000-0000-0000-0000-000000000902',
                    'Existing goal', 'Impression', 'unique_conversion'
                )
                """.trimIndent(),
            )
            pool.execute(
                """
                insert into experimentation.experiment_results (
                    experiment_id, variation_key, goal_id, impressions, observation_count,
                    conversions, conversion_rate
                ) values (
                    '00000000-0000-0000-0000-000000000902', 'control',
                    '00000000-0000-0000-0000-000000000903', 37, 37, 4, 0.108
                )
                """.trimIndent(),
            )

            FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))

            assertEquals(
                listOf("37"),
                pool.queryStrings("select assignments::text from experimentation.experiment_results"),
            )
            assertEquals(
                listOf("0"),
                pool.queryStrings("select cardinality(page_path_prefixes)::text from experimentation.conversion_goals"),
            )
            assertEquals(
                listOf("true"),
                pool.queryStrings("select (activation_filter is null)::text from experimentation.experiments"),
            )
        }

    @Test
    fun `V8 gives existing experiments an empty non-null account exclusion list`() =
        withDatabase("exp_v8_account_exclusions") { pool ->
            FlywayMigration(pool).migrate(listOf(PreAccountExclusionsMigration()))
            pool.execute(
                """
                insert into experimentation.feature_flags (
                    id, key, name, type, status, variations, default_variation_key
                ) values (
                    '00000000-0000-0000-0000-000000000701', 'exclusion-upgrade', 'Exclusion upgrade',
                    'boolean', 'enabled',
                    '[{"key":"control","name":"Control","description":"","value":false}]'::jsonb,
                    'control'
                )
                """.trimIndent(),
            )
            pool.execute(
                """
                insert into experimentation.experiments (
                    id, feature_flag_id, name, control_variation_key
                ) values (
                    '00000000-0000-0000-0000-000000000702',
                    '00000000-0000-0000-0000-000000000701',
                    'Existing experiment', 'control'
                )
                """.trimIndent(),
            )

            FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))

            assertEquals(
                listOf("0"),
                pool.queryStrings("select cardinality(excluded_principal_ids)::text from experimentation.experiments"),
            )
            assertEquals(
                listOf("NO"),
                pool.queryStrings(
                    """
                    select is_nullable from information_schema.columns
                    where table_schema = 'experimentation'
                      and table_name = 'experiments'
                      and column_name = 'excluded_principal_ids'
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun `V6 backfills the prior effective baseline and observation count`() = withDatabase("exp_v6_backfill") { pool ->
        FlywayMigration(pool).migrate(listOf(PreExplicitControlMigration()))
        pool.execute(
            """
            insert into experimentation.feature_flags (
                id, key, name, type, status, variations, default_variation_key, targeting_rules
            ) values (
                '00000000-0000-0000-0000-000000000101', 'upgrade-flag', 'Upgrade flag',
                'boolean', 'enabled',
                '[{"key":"z_palette","name":"Z","description":"","value":false},{"key":"a_palette","name":"a","description":"","value":true},{"key":"B_palette","name":"B","description":"","value":false},{"key":"y_rule","name":"Y","description":"","value":false},{"key":"a_rule","name":"a","description":"","value":true},{"key":"B_rule","name":"B","description":"","value":false}]'::jsonb,
                'z_palette',
                '[{"id":"rule-1","conditions":[],"rollout":{"variationWeights":[{"variationKey":"missing_from_palette","weight":10},{"variationKey":"a_rule","weight":45},{"variationKey":"B_rule","weight":45}]}}]'::jsonb
            )
            """.trimIndent(),
        )
        pool.execute(
            """
            insert into experimentation.experiments (id, feature_flag_id, name, targeting_rule_id)
            values
                ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000101', 'Default path', null),
                ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000101', 'Rule path', 'rule-1')
            """.trimIndent(),
        )
        pool.execute(
            """
            insert into experimentation.conversion_goals (id, experiment_id, name)
            values ('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000201', 'Existing goal')
            """.trimIndent(),
        )
        pool.execute(
            """
            insert into experimentation.experiment_results (
                experiment_id, variation_key, goal_id, impressions, conversions, conversion_rate
            ) values (
                '00000000-0000-0000-0000-000000000201', 'a_palette',
                '00000000-0000-0000-0000-000000000301', 7, 2, 0.2857142857
            )
            """.trimIndent(),
        )

        FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))

        assertEquals(
            listOf("B_palette", "B_rule"),
            pool.queryStrings(
                "select control_variation_key from experimentation.experiments order by id",
            ),
        )
        assertEquals(
            listOf("7"),
            pool.queryStrings("select observation_count::text from experimentation.experiment_results"),
        )
        assertEquals(
            listOf("NO"),
            pool.queryStrings(
                """
                select is_nullable from information_schema.columns
                where table_schema = 'experimentation'
                  and table_name = 'experiments'
                  and column_name = 'control_variation_key'
                """.trimIndent(),
            ),
        )
        assertTrue(
            "session_duration" in pool.queryStrings(
                "select unnest(enum_range(null::experimentation.goal_metric_type))::text",
            ),
        )
    }

    @Test
    fun `V6 fails when an existing experiment baseline cannot be resolved`() = withDatabase("exp_v6_failure") { pool ->
        FlywayMigration(pool).migrate(listOf(PreExplicitControlMigration()))
        pool.execute(
            """
            insert into experimentation.feature_flags (
                id, key, name, type, status, variations, default_variation_key
            ) values (
                '00000000-0000-0000-0000-000000000401', 'broken-flag', 'Broken flag',
                'boolean', 'enabled', '[]'::jsonb, 'missing'
            )
            """.trimIndent(),
        )
        pool.execute(
            """
            insert into experimentation.experiments (feature_flag_id, name)
            values ('00000000-0000-0000-0000-000000000401', 'Unresolvable baseline')
            """.trimIndent(),
        )

        val failure = assertFailsWith<Exception> {
            FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))
        }
        val messages = generateSequence(failure as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        assertTrue(messages.contains("Cannot resolve control variation"), messages)
    }

    @Test
    fun `V6 preserves the legacy palette fallback when an attached rule was removed`() =
        withDatabase("exp_v6_stale_rule") { pool ->
            FlywayMigration(pool).migrate(listOf(PreExplicitControlMigration()))
            pool.execute(
                """
                insert into experimentation.feature_flags (
                    id, key, name, type, status, variations, default_variation_key, targeting_rules
                ) values (
                    '00000000-0000-0000-0000-000000000451', 'stale-rule-flag', 'Stale rule flag',
                    'boolean', 'enabled',
                    '[{"key":"treatment","name":"Treatment","description":"","value":true},{"key":"control","name":"Control","description":"","value":false}]'::jsonb,
                    'control',
                    '[]'::jsonb
                )
                """.trimIndent(),
            )
            pool.execute(
                """
                insert into experimentation.experiments (feature_flag_id, name, targeting_rule_id)
                values ('00000000-0000-0000-0000-000000000451', 'Removed rule experiment', 'removed-rule')
                """.trimIndent(),
            )

            FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))

            assertEquals(
                listOf("control"),
                pool.queryStrings("select control_variation_key from experimentation.experiments"),
            )
            assertEquals(
                listOf("0"),
                pool.queryStrings("select analysis_revision::text from experimentation.experiments"),
            )
        }

    @Test
    fun `V6 pauses an active policy that treated the legacy control without discarding it`() = withDatabase("exp_v6_policy_conflict") { pool ->
        FlywayMigration(pool).migrate(listOf(PreExplicitControlMigration()))
        pool.execute(
            """
            insert into experimentation.feature_flags (
                id, key, name, type, status, variations, default_variation_key, targeting_rules
            ) values (
                '00000000-0000-0000-0000-000000000501', 'conflict-flag', 'Conflict flag',
                'boolean', 'enabled',
                '[{"key":"control","name":"Control","description":"","value":false},{"key":"treatment","name":"Treatment","description":"","value":true}]'::jsonb,
                'control',
                '[{"id":"rule-1","conditions":[],"rollout":{"variationWeights":[{"variationKey":"control","weight":50},{"variationKey":"treatment","weight":50}]}}]'::jsonb
            )
            """.trimIndent(),
        )
        pool.execute(
            """
            insert into experimentation.experiments (
                feature_flag_id, name, status, targeting_rule_id, rollout_policy
            ) values (
                '00000000-0000-0000-0000-000000000501', 'Conflicting policy', 'running', 'rule-1',
                '{"mode":"MANUAL","treatmentVariationKey":"control"}'::jsonb
            )
            """.trimIndent(),
        )

        FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))

        assertEquals(
            listOf("control"),
            pool.queryStrings("select control_variation_key from experimentation.experiments"),
        )
        assertEquals(
            listOf("paused"),
            pool.queryStrings("select status::text from experimentation.experiments"),
        )
        assertEquals(
            listOf("control"),
            pool.queryStrings("select rollout_policy ->> 'treatmentVariationKey' from experimentation.experiments"),
        )
    }

    @Test
    fun `V6 reproduces JVM UTF-16 ordering for supplementary variation keys`() =
        withDatabase("exp_v6_utf16_order") { pool ->
            FlywayMigration(pool).migrate(listOf(PreExplicitControlMigration()))
            pool.execute(
                """
                insert into experimentation.feature_flags (
                    id, key, name, type, status, variations, default_variation_key, targeting_rules
                ) values (
                    '00000000-0000-0000-0000-000000000601', 'unicode-flag', 'Unicode flag',
                    'string', 'enabled',
                    '[{"key":"\uE000","name":"BMP private use","description":"","value":"bmp"},{"key":"😀","name":"Supplementary","description":"","value":"supplementary"}]'::jsonb,
                    '\uE000',
                    '[{"id":"rule-1","conditions":[],"rollout":{"variationWeights":[{"variationKey":"\uE000","weight":50},{"variationKey":"😀","weight":50}]}}]'::jsonb
                )
                """.trimIndent(),
            )
            pool.execute(
                """
                insert into experimentation.experiments (id, feature_flag_id, name, targeting_rule_id)
                values
                    ('00000000-0000-0000-0000-000000000611', '00000000-0000-0000-0000-000000000601', 'Unicode default', null),
                    ('00000000-0000-0000-0000-000000000612', '00000000-0000-0000-0000-000000000601', 'Unicode rule', 'rule-1')
                """.trimIndent(),
            )

            FlywayMigration(pool).migrate(listOf(ExperimentationMigration()))

            val expected = listOf("😀", "\uE000").sorted().first()
            assertEquals(
                listOf(expected, expected),
                pool.queryStrings("select control_variation_key from experimentation.experiments order by id"),
            )
    }

    private fun withDatabase(name: String, block: suspend (ConnectionPool) -> Unit) = runBlocking {
        val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName(name)
            start()
        }
        val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 2,
                ),
                key = name,
            ),
        )
        try {
            block(pool)
        } finally {
            pool.close()
            postgres.stop()
        }
    }

    private suspend fun ConnectionPool.execute(sql: String) {
        connection().use { connection ->
            connection.useStatement(sql) { statement -> statement.execute() }
        }
    }

    private suspend fun ConnectionPool.queryStrings(sql: String): List<String> =
        connection().use { connection ->
            connection.useStatement(sql) { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                }
            }
        }
}
