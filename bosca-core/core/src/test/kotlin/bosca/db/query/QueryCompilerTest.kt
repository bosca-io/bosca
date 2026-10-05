package bosca.db.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryCompilerTest {

    @Test
    fun `test select case insensitivity`() {
        val query = "sElEcT * FROM table"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
    }

    @Test
    fun `test query with leading whitespace and newlines`() {
        val query = "\n  \t UPDATE table SET col = 1"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.UPDATE, compiled.type)
    }

    @Test
    fun `test insert query type`() {
        val query = "INSERT INTO table (id) VALUES (1)"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.INSERT, compiled.type)
    }

    @Test
    fun `test delete query type`() {
        val query = "DELETE FROM table WHERE id = 1"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.DELETE, compiled.type)
    }

    @Test
    fun `test invalid query type throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            QueryCompiler.compileQuery("FOO * FROM table")
        }
    }

    @Test
    fun `test keyword not at start throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            QueryCompiler.compileQuery("SELECT * FROM table".padStart(25, '.'))
        }
    }

    @Test
    fun `test keyword as prefix should fail`() {
        assertFailsWith<IllegalArgumentException> {
            QueryCompiler.compileQuery("SELECTING * FROM table")
        }
    }

    @Test
    fun `test query with dollar delimiter`() {
        val query = "SELECT * FROM table WHERE id = $1"
        val compiled = QueryCompiler.compileQuery(query, '$')
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals("SELECT * FROM table WHERE id = ?", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("1", compiled.parameters[0].name)
    }

    @Test
    fun `test simple select`() {
        val query = "SELECT * FROM table"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals(query, compiled.originalQuery)
        assertEquals(query, compiled.sql)
        assertEquals(0, compiled.parameters.size)
    }

    @Test
    fun `test select with single parameter`() {
        val query = "SELECT * FROM table WHERE id = :id"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals("SELECT * FROM table WHERE id = ?", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("id", compiled.parameters[0].name)
        assertEquals(0, compiled.parameters[0].offset)
        assertEquals(31, compiled.parameters[0].position)
    }

    @Test
    fun `test select with multiple parameters`() {
        val query = "SELECT * FROM table WHERE id = :id AND name = :name"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM table WHERE id = ? AND name = ?", compiled.sql)
        assertEquals(2, compiled.parameters.size)
        assertEquals("id", compiled.parameters[0].name)
        assertEquals("name", compiled.parameters[1].name)
    }

    @Test
    fun `test parameter at the end`() {
        val query = "DELETE FROM table WHERE id = :id"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.DELETE, compiled.type)
        assertEquals("DELETE FROM table WHERE id = ?", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("id", compiled.parameters[0].name)
    }

    @Test
    fun `test postgres type casting`() {
        val query = "SELECT '123'::integer"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT '123'::integer", compiled.sql)
        assertEquals(0, compiled.parameters.size)
    }

    @Test
    fun `test parameter with postgres type casting`() {
        val query = "INSERT INTO table (val) VALUES (:val::jsonb)"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.INSERT, compiled.type)
        assertEquals("INSERT INTO table (val) VALUES (?::jsonb)", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("val", compiled.parameters[0].name)
    }

    @Test
    fun `test complex postgres query`() {
        val query = """
            INSERT INTO profile_guide_progress as p (profile_id, metadata_id, version, attributes, completed_step_ids) 
            VALUES (:profileId, :metadataId, :version, :attributes, ARRAY[:stepId]::bigint[]) 
            ON CONFLICT (profile_id, metadata_id, version) 
            DO UPDATE SET modified = now(), attributes = coalesce(p.attributes, '{}'::jsonb) || :attributes, completed_step_ids = array_append(p.completed_step_ids, :stepId) 
            WHERE NOT (p.completed_step_ids @> ARRAY[:stepId]::bigint[]) 
            RETURNING *
        """.trimIndent()
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.INSERT, compiled.type)
        assertEquals(8, compiled.parameters.size)
        assertEquals("profileId", compiled.parameters[0].name)
        assertEquals("metadataId", compiled.parameters[1].name)
        assertEquals("version", compiled.parameters[2].name)
        assertEquals("attributes", compiled.parameters[3].name)
        assertEquals("stepId", compiled.parameters[4].name)
        assertEquals("attributes", compiled.parameters[5].name)
        assertEquals("stepId", compiled.parameters[6].name)
        assertEquals("stepId", compiled.parameters[7].name)
        
        // Count how many '?' are in the SQL
        val questionMarks = compiled.sql.count { it == '?' }
        assertEquals(8, questionMarks)
    }

    @Test
    fun `test update query`() {
        val query = "UPDATE table SET name = :name WHERE id = :id"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.UPDATE, compiled.type)
        assertEquals("UPDATE table SET name = ? WHERE id = ?", compiled.sql)
        assertEquals(2, compiled.parameters.size)
    }

    @Test
    fun `test unsupported query type`() {
        assertFailsWith<IllegalArgumentException> {
            QueryCompiler.compileQuery("DROP TABLE table")
        }
    }

    @Test
    fun `test parameter name with underscore`() {
        val query = "SELECT * FROM table WHERE my_id = :my_id"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM table WHERE my_id = ?", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("my_id", compiled.parameters[0].name)
    }

    @Test
    fun `test double colon is not a parameter`() {
        val query = "SELECT * FROM table WHERE col :: text = :val"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM table WHERE col :: text = ?", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("val", compiled.parameters[0].name)
    }

    @Test
    fun `test compile from input stream`() {
        val script = """
            SELECT * FROM table1;
            INSERT INTO table2 (id) VALUES (:id);
            UPDATE table3 SET name = 'foo'
            WHERE id = 1;
        """.trimIndent()
        val stream = script.byteInputStream()
        val queries = QueryCompiler.compile(stream)
        assertEquals(3, queries.size)
        assertEquals("SELECT * FROM table1", queries[0])
        assertEquals("INSERT INTO table2 (id) VALUES (:id)", queries[1])
        assertEquals("UPDATE table3 SET name = 'foo' WHERE id = 1", queries[2])
    }

    @Test
    fun `test inner join query`() {
        val query = "SELECT groups.* FROM principal_groups INNER JOIN groups ON (principal_groups.group_id = groups.id) WHERE principal_groups.principal = :id"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals("SELECT groups.* FROM principal_groups INNER JOIN groups ON (principal_groups.group_id = groups.id) WHERE principal_groups.principal = ?", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("id", compiled.parameters[0].name)
    }

    @Test
    fun `test multiple joins query`() {
        val query = "SELECT cc.* FROM chat_channels cc JOIN chat_channel_members ccm1 ON cc.id = ccm1.channel_id JOIN chat_channel_members ccm2 ON cc.id = ccm2.channel_id WHERE cc.type = 'direct' AND ccm1.profile_id = :profileId1 AND ccm2.profile_id = :profileId2"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals("SELECT cc.* FROM chat_channels cc JOIN chat_channel_members ccm1 ON cc.id = ccm1.channel_id JOIN chat_channel_members ccm2 ON cc.id = ccm2.channel_id WHERE cc.type = 'direct' AND ccm1.profile_id = ? AND ccm2.profile_id = ?", compiled.sql)
        assertEquals(2, compiled.parameters.size)
        assertEquals("profileId1", compiled.parameters[0].name)
        assertEquals("profileId2", compiled.parameters[1].name)
    }

    @Test
    fun `test left join with complex where and offset limit`() {
        val query = "SELECT collection_items.* FROM collection_items LEFT JOIN collections ON (collection_items.child_collection_id = collections.id AND collections.workflow_state_id = :state) WHERE collection_id = :id AND (collections.id IS NOT NULL) OFFSET :offset LIMIT :limit"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals("SELECT collection_items.* FROM collection_items LEFT JOIN collections ON (collection_items.child_collection_id = collections.id AND collections.workflow_state_id = ?) WHERE collection_id = ? AND (collections.id IS NOT NULL) OFFSET ? LIMIT ?", compiled.sql)
        assertEquals(4, compiled.parameters.size)
        assertEquals("state", compiled.parameters[0].name)
        assertEquals("id", compiled.parameters[1].name)
        assertEquals("offset", compiled.parameters[2].name)
        assertEquals("limit", compiled.parameters[3].name)
    }

    @Test
    fun `test query with CASE and JSONB operators`() {
        val query = "UPDATE metadata_relationships SET attributes = :attributes || (CASE WHEN jsonb_typeof(attributes) = 'null' THEN '{}'::jsonb ELSE attributes END) WHERE metadata1_id = :id1 AND metadata2_id = :id2 AND relationship = :relationship"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.UPDATE, compiled.type)
        assertEquals("UPDATE metadata_relationships SET attributes = ? || (CASE WHEN jsonb_typeof(attributes) = 'null' THEN '{}'::jsonb ELSE attributes END) WHERE metadata1_id = ? AND metadata2_id = ? AND relationship = ?", compiled.sql)
        assertEquals(4, compiled.parameters.size)
        assertEquals("attributes", compiled.parameters[0].name)
        assertEquals("id1", compiled.parameters[1].name)
        assertEquals("id2", compiled.parameters[2].name)
        assertEquals("relationship", compiled.parameters[3].name)
    }

    @Test
    fun `test query with ANY`() {
        val query = "SELECT * FROM metadata WHERE id = ANY(:ids)"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals("SELECT * FROM metadata WHERE id = ANY(?)", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("ids", compiled.parameters[0].name)
    }

    @Test
    fun `test query with subquery`() {
        val query = "SELECT id FROM metadata WHERE (parent_id = (SELECT parent_id FROM metadata WHERE id = :id LIMIT 1) OR id = (SELECT parent_id FROM metadata WHERE id = :id LIMIT 1) OR parent_id = :id) AND language_tag = :languageTag LIMIT 1"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals("SELECT id FROM metadata WHERE (parent_id = (SELECT parent_id FROM metadata WHERE id = ? LIMIT 1) OR id = (SELECT parent_id FROM metadata WHERE id = ? LIMIT 1) OR parent_id = ?) AND language_tag = ? LIMIT 1", compiled.sql)
        assertEquals(4, compiled.parameters.size)
        assertEquals("id", compiled.parameters[0].name)
        assertEquals("id", compiled.parameters[1].name)
        assertEquals("id", compiled.parameters[2].name)
        assertEquals("languageTag", compiled.parameters[3].name)
    }

    @Test
    fun `test query with LOWER function`() {
        val query = "SELECT * FROM table WHERE LOWER(name) = LOWER(:name)"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM table WHERE LOWER(name) = LOWER(?)", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("name", compiled.parameters[0].name)
    }

    @Test
    fun `test query with JSONB arrow operators`() {
        val query = "SELECT * FROM principals WHERE attributes->>'identifier' = :identifier"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM principals WHERE attributes->>'identifier' = ?", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("identifier", compiled.parameters[0].name)
    }

    @Test
    fun `test query with LIKE and string concat`() {
        val query = "SELECT * FROM groups WHERE (lower(name) like lower(:name) || '%')"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM groups WHERE (lower(name) like lower(?) || '%')", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("name", compiled.parameters[0].name)
    }

    @Test
    fun `test complex order by with coalesce`() {
        val query = "SELECT * FROM collection_metadata_relationships ORDER BY coalesce((attributes->>'sort')::int, 0) ASC, metadata_id ASC"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM collection_metadata_relationships ORDER BY coalesce((attributes->>'sort')::int, 0) ASC, metadata_id ASC", compiled.sql)
        assertEquals(0, compiled.parameters.size)
    }

    @Test
    fun `test on conflict with multiple columns`() {
        val query = "INSERT INTO table (id, tag) VALUES (:id, :tag) ON CONFLICT (id, tag) DO UPDATE SET tag = :tag"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("INSERT INTO table (id, tag) VALUES (?, ?) ON CONFLICT (id, tag) DO UPDATE SET tag = ?", compiled.sql)
        assertEquals(3, compiled.parameters.size)
        assertEquals("id", compiled.parameters[0].name)
        assertEquals("tag", compiled.parameters[1].name)
        assertEquals("tag", compiled.parameters[2].name)
    }

    @Test
    fun `test ANY with whitespace`() {
        val query = "SELECT * FROM principals WHERE id = ANY (:id)"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM principals WHERE id = ANY (?)", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("id", compiled.parameters[0].name)
    }

    @Test
    fun `test parameter in parentheses with type cast`() {
        val query = "SELECT * FROM table WHERE type = (:type)::my_type"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM table WHERE type = (?)::my_type", compiled.sql)
        assertEquals(1, compiled.parameters.size)
        assertEquals("type", compiled.parameters[0].name)
    }

    @Test
    fun `test IN clause with multiple parameters`() {
        val query = "SELECT * FROM table WHERE id IN (:id1, :id2, :id3)"
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals("SELECT * FROM table WHERE id IN (?, ?, ?)", compiled.sql)
        assertEquals(3, compiled.parameters.size)
        assertEquals("id1", compiled.parameters[0].name)
        assertEquals("id2", compiled.parameters[1].name)
        assertEquals("id3", compiled.parameters[2].name)
    }

    @Test
    fun `test query with comments`() {
        val query = """
            SELECT * FROM table -- another comment
            WHERE id = :id /* multi-line
            comment */ AND name = :name
        """.trimIndent()
        val compiled = QueryCompiler.compileQuery(query)
        assertEquals(QueryType.SELECT, compiled.type)
        assertEquals("SELECT * FROM table -- another comment\nWHERE id = ? /* multi-line\ncomment */ AND name = ?", compiled.sql)
        assertEquals(2, compiled.parameters.size)
        assertEquals("id", compiled.parameters[0].name)
        assertEquals("name", compiled.parameters[1].name)
    }

    @Test
    fun `test invalid query type does not throw exception when configured`() {
        val compiled = QueryCompiler.compileQuery("FOO * FROM table", throwOnUnknown = false)
        assertEquals(QueryType.UNKNOWN, compiled.type)
    }
}
