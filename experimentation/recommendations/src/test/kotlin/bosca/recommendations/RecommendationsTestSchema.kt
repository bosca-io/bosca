package bosca.recommendations

import bosca.db.connection

/**
 * Creates the `public`-schema prerequisites that the recommendations migration references but the
 * integration tests don't otherwise stub: the profile attributes used by
 * `recommendations.profile_cohort`, plus the content tables queried when recommendation context
 * membership is applied before ranking and pagination.
 *
 * Call inside an active DB-context block (so [connection] resolves) and BEFORE running
 * `RecommendationsMigration`, alongside the other `public` stubs each test creates.
 */
suspend fun createProfileAttributeSignalsPrerequisites() {
    connection().useStatement("CREATE EXTENSION IF NOT EXISTS vector") { it.execute() }
    connection().useStatement(
        "CREATE TABLE IF NOT EXISTS public.language_resolution_contexts (id uuid PRIMARY KEY, key varchar NOT NULL UNIQUE, fallback_language_tag varchar NOT NULL DEFAULT 'en')",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.language_resolution_contexts ADD COLUMN IF NOT EXISTS fallback_language_tag varchar NOT NULL DEFAULT 'en'",
    ) { it.execute() }
    connection().useStatement(
        """
        CREATE TABLE IF NOT EXISTS public.language_tag_mappings (
            context_id uuid NOT NULL REFERENCES public.language_resolution_contexts(id) ON DELETE CASCADE,
            source_language_tag varchar NOT NULL,
            resolved_language_tag varchar NOT NULL,
            PRIMARY KEY (context_id, source_language_tag)
        )
        """.trimIndent(),
    ) { it.execute() }
    connection().useStatement(
        "INSERT INTO public.language_resolution_contexts (id, key) VALUES ('00000000-0000-0000-0000-000000000001', 'recommendations') ON CONFLICT (key) DO NOTHING",
    ) { it.execute() }
    connection().useStatement(
        """
        INSERT INTO public.language_tag_mappings (context_id, source_language_tag, resolved_language_tag)
        SELECT context.id, mapping.source_language_tag, mapping.resolved_language_tag
        FROM public.language_resolution_contexts context
        CROSS JOIN (VALUES
            ('en', 'en'),
            ('en-US', 'en'),
            ('es', 'es'),
            ('fr', 'fr')
        ) AS mapping(source_language_tag, resolved_language_tag)
        WHERE context.key = 'recommendations'
        ON CONFLICT (context_id, source_language_tag)
        DO UPDATE SET resolved_language_tag = excluded.resolved_language_tag
        """.trimIndent(),
    ) { it.execute() }
    connection().useStatement(
        """
        CREATE TABLE IF NOT EXISTS public.metadata (
            id uuid PRIMARY KEY,
            language_tag varchar NOT NULL DEFAULT 'en',
            content_type varchar NOT NULL DEFAULT 'bosca/v-document',
            attributes jsonb NOT NULL DEFAULT '{}',
            deleted boolean NOT NULL DEFAULT false,
            recommendation_contexts text[] NOT NULL DEFAULT '{}',
            recommendable boolean NOT NULL DEFAULT true
        )
        """.trimIndent(),
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.metadata ADD COLUMN IF NOT EXISTS recommendable boolean NOT NULL DEFAULT true",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.metadata ADD COLUMN IF NOT EXISTS language_tag varchar NOT NULL DEFAULT 'en'",
    ) { it.execute() }
    connection().useStatement(
        """
        CREATE TABLE IF NOT EXISTS public.collections (
            id uuid PRIMARY KEY,
            language_tag varchar NOT NULL DEFAULT 'en',
            type varchar NOT NULL DEFAULT 'standard',
            attributes jsonb NOT NULL DEFAULT '{}',
            deleted boolean NOT NULL DEFAULT false,
            recommendation_contexts text[] NOT NULL DEFAULT '{}',
            recommendable boolean NOT NULL DEFAULT true
        )
        """.trimIndent(),
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS recommendable boolean NOT NULL DEFAULT true",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.collections ADD COLUMN IF NOT EXISTS language_tag varchar NOT NULL DEFAULT 'en'",
    ) { it.execute() }
    connection().useStatement(
        """
        CREATE TABLE IF NOT EXISTS public.collection_language_variants (
            id uuid NOT NULL REFERENCES public.collections(id) ON DELETE CASCADE,
            language_tag varchar NOT NULL,
            recommendable boolean NOT NULL DEFAULT true,
            PRIMARY KEY (id, language_tag)
        )
        """.trimIndent(),
    ) { it.execute() }
    connection().useStatement(
        """
        CREATE TABLE IF NOT EXISTS public.metadata_embeddings (
            metadata_id uuid NOT NULL REFERENCES public.metadata(id) ON DELETE CASCADE,
            chunk_index integer NOT NULL,
            token_start integer NOT NULL,
            token_end integer NOT NULL,
            token_count integer NOT NULL,
            aggregation_weight double precision NOT NULL,
            embedding vector(768) NOT NULL,
            PRIMARY KEY (metadata_id, chunk_index)
        )
        """.trimIndent(),
    ) { it.execute() }
    // Upgrade a reused test database that still has the former one-row-per-metadata table shape.
    connection().useStatement(
        "ALTER TABLE public.metadata_embeddings ADD COLUMN IF NOT EXISTS chunk_index integer NOT NULL DEFAULT 0",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.metadata_embeddings ADD COLUMN IF NOT EXISTS token_count integer NOT NULL DEFAULT 1",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.metadata_embeddings ADD COLUMN IF NOT EXISTS token_start integer NOT NULL DEFAULT 0",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.metadata_embeddings ADD COLUMN IF NOT EXISTS token_end integer NOT NULL DEFAULT 1",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.metadata_embeddings ADD COLUMN IF NOT EXISTS aggregation_weight double precision NOT NULL DEFAULT 1",
    ) { it.execute() }
    connection().useStatement(
        "UPDATE public.metadata_embeddings SET token_count = 1 WHERE token_count <= 0",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.metadata_embeddings DROP CONSTRAINT IF EXISTS metadata_embeddings_pkey",
    ) { it.execute() }
    connection().useStatement(
        "ALTER TABLE public.metadata_embeddings ADD PRIMARY KEY (metadata_id, chunk_index)",
    ) { it.execute() }
    connection().useStatement(
        """
        CREATE TABLE IF NOT EXISTS public.profile_attributes (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            profile uuid NOT NULL,
            type_id varchar NOT NULL,
            priority int NOT NULL DEFAULT 0,
            signals jsonb
        )
        """.trimIndent(),
    ) { it.execute() }
    connection().useStatement(
        """
        CREATE OR REPLACE VIEW public.profile_attribute_signals AS
        select pa.profile as user_id, sig.elem ->> 'key' as signal_key,
               (sig.elem -> 'value')::text as signal_value, pa.priority as attribute_priority
        from public.profile_attributes pa
        cross join lateral jsonb_array_elements(pa.signals) as sig(elem)
        where pa.signals is not null
        """.trimIndent(),
    ) { it.execute() }
}
