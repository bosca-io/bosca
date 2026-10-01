package bosca.backup

/**
 * Represents a database table that participates in backup and restore operations.
 * Tables are ordered by foreign-key dependency so that restore inserts succeed
 * without violating referential integrity constraints.
 *
 * @property name The SQL table name as it appears in the database.
 * @property primaryKeys The column names forming the table's primary key, used
 *   to build ON CONFLICT clauses during restore.
 * @property schema The database schema containing this table, defaults to `public`.
 */
data class BackupTableDefinition(
    val name: String,
    val primaryKeys: List<String>,
    val schema: String = "public"
) {
    /** Schema-qualified table name for use in SQL statements. */
    val qualifiedName: String get() = "\"$schema\".\"$name\""
}

/**
 * Provides the ordered list of all database tables eligible for backup and restore.
 * Tables are arranged in phases by foreign-key dependency: independent reference
 * tables first, then tables that depend on them, and finally junction and history
 * tables last. This ordering ensures that restore inserts respect referential
 * integrity without needing to defer constraint checks.
 */
object BackupTables {

    /** All tables in dependency order, suitable for both backup export and restore import. */
    val ordered: List<BackupTableDefinition> = listOf(
        // Phase 1: Independent reference tables (no foreign keys to other app tables)
        BackupTableDefinition("traits", listOf("id")),
        BackupTableDefinition("categories", listOf("id")),
        BackupTableDefinition("sources", listOf("id")),
        BackupTableDefinition("models", listOf("id")),
        BackupTableDefinition("prompts", listOf("id")),
        BackupTableDefinition("workflows", listOf("id")),
        BackupTableDefinition("activities", listOf("id")),
        BackupTableDefinition("languages", listOf("tag")),
        BackupTableDefinition("profile_attribute_types", listOf("id")),
        BackupTableDefinition("groups", listOf("id")),
        BackupTableDefinition("time_event_types", listOf("id")),

        // Phase 2: Tables depending on Phase 1
        BackupTableDefinition("trait_workflows", listOf("trait_id", "workflow_id")),
        BackupTableDefinition("trait_content_types", listOf("trait_id", "content_type")),
        BackupTableDefinition("activity_inputs", listOf("activity_id", "name")),
        BackupTableDefinition("activity_outputs", listOf("activity_id", "name")),
        BackupTableDefinition("workflow_activities", listOf("id")),
        BackupTableDefinition("workflow_states", listOf("id")),
        BackupTableDefinition("states", listOf("id")),
        BackupTableDefinition("storage_systems", listOf("id")),
        BackupTableDefinition("principals", listOf("id")),
        BackupTableDefinition("configurations", listOf("id")),
        BackupTableDefinition("organizations", listOf("id")),
        BackupTableDefinition("template_attribute_tools", listOf("id")),
        BackupTableDefinition("agents", listOf("id")),
        BackupTableDefinition("agent_tools", listOf("id")),

        // Phase 3: Tables depending on Phase 2
        BackupTableDefinition("workflow_state_transitions", listOf("from_state_id", "to_state_id")),
        BackupTableDefinition("state_transitions", listOf("from_state_id", "to_state_id")),
        BackupTableDefinition("workflow_activity_inputs", listOf("activity_id", "name")),
        BackupTableDefinition("workflow_activity_outputs", listOf("activity_id", "name")),
        BackupTableDefinition("workflow_activity_storage_systems", listOf("activity_id", "storage_system_id")),
        BackupTableDefinition("workflow_activity_models", listOf("activity_id", "model_id")),
        BackupTableDefinition("workflow_activity_prompts", listOf("activity_id", "prompt_id")),
        BackupTableDefinition("workflow_schedules", listOf("id", "workflow_id")),
        BackupTableDefinition("workflow_plans", listOf("id")),
        BackupTableDefinition("workflow_events", listOf("event_name", "workflow_id")),
        BackupTableDefinition("storage_system_models", listOf("system_id", "model_id")),
        BackupTableDefinition("principal_credentials", listOf("id")),
        BackupTableDefinition("principal_groups", listOf("principal", "group_id")),
        BackupTableDefinition("principal_refresh_tokens", listOf("token")),
        BackupTableDefinition("profiles", listOf("id")),
        BackupTableDefinition("configuration_values", listOf("configuration_id")),
        BackupTableDefinition("configuration_permissions", listOf("entity_id", "group_id", "action")),
        BackupTableDefinition("organization_domains", listOf("organization_id", "domain")),
        BackupTableDefinition("organization_members", listOf("organization_id", "principal_id")),
        BackupTableDefinition("organization_permissions", listOf("organization_id", "group_id", "action")),
        BackupTableDefinition("organization_signup_email", listOf("email")),
        BackupTableDefinition("organization_signup_tokens", listOf("token")),
        BackupTableDefinition("agent_agent_tools", listOf("agent_id", "tool_id")),
        BackupTableDefinition("agent_sub_agents", listOf("agent_id", "sub_agent_id")),

        // Phase 4: Content tables
        BackupTableDefinition("collections", listOf("id")),
        BackupTableDefinition("metadata", listOf("id")),

        // Phase 5: Content junction and dependent tables
        BackupTableDefinition("collection_items", listOf("id")),
        BackupTableDefinition("collection_traits", listOf("collection_id", "trait_id")),
        BackupTableDefinition("collection_categories", listOf("collection_id", "category_id")),
        BackupTableDefinition("collection_permissions", listOf("collection_id", "group_id", "action")),
        BackupTableDefinition("collection_supplementary", listOf("id")),
        BackupTableDefinition("collection_supplementary_traits", listOf("id")),
        BackupTableDefinition("collection_language_variants", listOf("id", "language_tag")),
        BackupTableDefinition("collection_collaborations", listOf("collection_id", "language_tag")),
        BackupTableDefinition("collection_job_history", listOf("id", "job_id")),
        BackupTableDefinition("collection_workflow_transition_history", listOf("id")),
        BackupTableDefinition("collection_workflow_plans", listOf("id", "plan_id")),
        BackupTableDefinition("collection_metadata_relationships", listOf("collection_id", "metadata_id", "relationship")),
        BackupTableDefinition("collection_variant_metadata_relationships", listOf("collection_id", "language_tag", "metadata_id", "relationship")),

        BackupTableDefinition("metadata_traits", listOf("metadata_id", "trait_id")),
        BackupTableDefinition("metadata_categories", listOf("metadata_id", "category_id")),
        BackupTableDefinition("metadata_permissions", listOf("metadata_id", "group_id", "action")),
        BackupTableDefinition("metadata_supplementary", listOf("id")),
        BackupTableDefinition("metadata_supplementary_traits", listOf("id")),
        BackupTableDefinition("metadata_relationships", listOf("metadata1_id", "metadata2_id", "relationship")),
        BackupTableDefinition("metadata_profiles", listOf("metadata_id", "profile_id", "relationship")),
        BackupTableDefinition("metadata_job_history", listOf("id", "version", "job_id")),
        BackupTableDefinition("metadata_workflow_transition_history", listOf("id")),
        BackupTableDefinition("metadata_workflow_plans", listOf("id", "plan_id")),
        BackupTableDefinition("metadata_comments", listOf("id")),
        BackupTableDefinition("metadata_comment_likes", listOf("comment_id", "profile_id")),

        // Phase 6: Versioned metadata tables
        BackupTableDefinition("metadata_versions", listOf("id", "version")),
        BackupTableDefinition("metadata_versions_supplementary", listOf("metadata_id", "version", "key")),
        BackupTableDefinition("metadata_version_traits", listOf("metadata_id", "version", "trait_id")),
        BackupTableDefinition("metadata_version_categories", listOf("metadata_id", "version", "category_id")),
        BackupTableDefinition("metadata_version_supplementary_traits", listOf("metadata_id", "version", "key", "trait_id")),
        BackupTableDefinition("metadata_version_profiles", listOf("metadata_id", "version", "profile_id", "relationship")),

        // Phase 7: Template and document tables
        BackupTableDefinition("collection_templates", listOf("metadata_id", "version")),
        BackupTableDefinition("collection_template_attributes", listOf("metadata_id", "version", "key")),
        BackupTableDefinition("collection_template_attribute_workflows", listOf("metadata_id", "version", "key", "workflow_id")),
        BackupTableDefinition("document_templates", listOf("metadata_id", "version")),
        BackupTableDefinition("document_template_containers", listOf("metadata_id", "version", "id")),
        BackupTableDefinition("document_template_container_workflows", listOf("metadata_id", "version", "id", "workflow_id")),
        BackupTableDefinition("document_template_attributes", listOf("metadata_id", "version", "key")),
        BackupTableDefinition("document_template_attribute_workflows", listOf("metadata_id", "version", "key", "workflow_id")),
        BackupTableDefinition("documents", listOf("metadata_id", "version")),
        BackupTableDefinition("document_collaborations", listOf("metadata_id", "version")),
        BackupTableDefinition("guide_templates", listOf("metadata_id", "version")),
        BackupTableDefinition("guide_template_attributes", listOf("metadata_id", "version", "key")),
        BackupTableDefinition("guide_template_steps", listOf("metadata_id", "version", "id")),
        BackupTableDefinition("guide_template_step_modules", listOf("metadata_id", "version", "step", "id")),
        BackupTableDefinition("guides", listOf("metadata_id", "version")),
        BackupTableDefinition("guide_steps", listOf("metadata_id", "version", "id")),
        BackupTableDefinition("guide_step_modules", listOf("metadata_id", "version", "step", "id")),
        BackupTableDefinition("data_templates", listOf("metadata_id", "version")),
        BackupTableDefinition("data_template_attributes", listOf("metadata_id", "version", "key")),
        BackupTableDefinition("data_template_attribute_workflows", listOf("metadata_id", "version", "key", "workflow_id")),
        BackupTableDefinition("data", listOf("metadata_id", "version")),
        BackupTableDefinition("data_collaborations", listOf("metadata_id", "version")),

        // Phase 8: Profile, community, and analytics tables
        BackupTableDefinition("profile_attributes", listOf("id")),
        BackupTableDefinition("profile_bookmarks", listOf("id")),
        BackupTableDefinition("profile_ratings", listOf("id")),
        BackupTableDefinition("profile_relationships", listOf("profile_id_1", "profile_id_2", "type")),
        BackupTableDefinition("profile_marks", listOf("id")),
        BackupTableDefinition("profile_guide_history", listOf("id")),
        BackupTableDefinition("profile_guide_progress", listOf("profile_id", "metadata_id", "version")),

        BackupTableDefinition("community_groups", listOf("id")),
        BackupTableDefinition("community_group_members", listOf("group_id", "profile_id")),
        BackupTableDefinition("community_group_permissions", listOf("community_group_id", "group_id", "action")),
        BackupTableDefinition("community_group_signup_email", listOf("email")),
        BackupTableDefinition("community_group_signup_tokens", listOf("token")),
        BackupTableDefinition("community_signup_emails", listOf("email", "group_id")),
        BackupTableDefinition("community_activities", listOf("id")),
        BackupTableDefinition("prayers", listOf("id"), schema = "community"),
        BackupTableDefinition("prayer_permissions", listOf("prayer_id", "group_id", "action"), schema = "community"),
        BackupTableDefinition("prayer_comments", listOf("id"), schema = "community"),

        BackupTableDefinition("analytics_queries", listOf("id")),
        BackupTableDefinition("analytics_query_parameters", listOf("query_id", "parameter")),
        BackupTableDefinition("analytics_query_permissions", listOf("query_id", "group_id", "action")),
        BackupTableDefinition("analytics_dashboards", listOf("id")),
        BackupTableDefinition("analytics_dashboard_permissions", listOf("dashboard_id", "group_id", "action")),
        BackupTableDefinition("analytics_visualizations", listOf("id")),
        BackupTableDefinition("analytics_visualization_permissions", listOf("visualization_id", "group_id", "action")),
        BackupTableDefinition("analytics_dashboard_visualizations", listOf("id")),

        // Phase 9: Miscellaneous tables
        BackupTableDefinition("slugs", listOf("slug")),
        BackupTableDefinition("gql_persisted_queries", listOf("application", "sha256")),
        BackupTableDefinition("package_installations", listOf("id")),
        BackupTableDefinition("events", listOf("type", "workflow_id")),
        BackupTableDefinition("time_events", listOf("id")),
        BackupTableDefinition("chat_channels", listOf("id")),
        BackupTableDefinition("chat_channel_members", listOf("channel_id", "profile_id")),
        BackupTableDefinition("chat_channel_permissions", listOf("channel_id", "group_id", "action")),

        // Phase 10: Bible tables
        BackupTableDefinition("bibles", listOf("metadata_id", "version", "variant")),
        BackupTableDefinition("bible_languages", listOf("metadata_id", "version", "variant", "iso")),
        BackupTableDefinition("bible_books", listOf("metadata_id", "version", "variant", "usfm")),
        BackupTableDefinition("bible_chapters", listOf("metadata_id", "version", "variant", "book_usfm", "usfm")),

        // Phase 11: API documentation
        BackupTableDefinition("api_documentation", listOf("qualified_name")),

        // Phase 12: AI schema tables
        BackupTableDefinition("chat_sessions", listOf("id"), "ai"),
        BackupTableDefinition("chat_messages", listOf("id"), "ai"),
        BackupTableDefinition("mcp_server_registrations", listOf("id"), "ai"),

        // Phase 13: Scheduler schema tables
        BackupTableDefinition("scheduled_jobs", listOf("id"), "scheduler"),
        BackupTableDefinition("job_history", listOf("id"), "scheduler"),

        // Phase 14: Scripting schema tables
        BackupTableDefinition("scripts", listOf("id"), "scripting"),
        BackupTableDefinition("trigger_bindings", listOf("id"), "scripting"),
    )
}
