CREATE OR REPLACE FUNCTION calculate_collection_etag(p_collection_id uuid) RETURNS varchar AS
$$
DECLARE
    v_modified   timestamp;
    v_item_count integer;
    v_etag       varchar;
BEGIN

    SELECT modified
    INTO v_modified
    FROM collections
    WHERE id = p_collection_id;

    SELECT count(*) FROM collection_items WHERE collection_id = p_collection_id INTO v_item_count;

    v_etag := MD5(
      COALESCE(v_modified::text, 'null') || '-' ||
      COALESCE(v_item_count::text, '0')
    );

    UPDATE collections
    SET etag = v_etag
    WHERE id = p_collection_id;

    RETURN v_etag;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION calculate_metadata_etag(p_metadata_id uuid) RETURNS varchar AS
$$
DECLARE
    v_version        integer;
    v_content_type   varchar;
    v_title          varchar;
    v_content        jsonb;
    v_modified       timestamp;
    v_content_length integer;
    v_etag           varchar;
BEGIN
    SELECT version, modified, content_length, content_type
    INTO v_version, v_modified, v_content_length, v_content_type
    FROM metadata
    WHERE id = p_metadata_id;

    if v_content_type = 'bosca/v-document' then
        select calculate_metadata_document_etag(p_metadata_id) into v_etag;
        return v_etag;
    elsif v_content_type = 'bosca/v-guide' or v_content_type = 'bosca/v-guide-step' or v_content_type = 'bosca/v-guide-module' then
        select calculate_metadata_guide_etag(p_metadata_id) into v_etag;
        return v_etag;
    end if;

    if v_content_length is null then
        v_content_length := 0;
    end if;

    v_content_length := v_content_length + length(v_title) + length(v_content::text);

    v_etag := MD5(
        COALESCE(v_modified::text, 'null') || '-' ||
        COALESCE(v_content_length::text, '0')
    );

    UPDATE metadata
    SET etag = v_etag
    WHERE id = p_metadata_id;

    RETURN v_etag;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION calculate_metadata_document_etag(p_metadata_id uuid) RETURNS varchar AS
$$
DECLARE
    v_version        integer;
    v_title          varchar;
    v_content        jsonb;
    v_modified       timestamp;
    v_content_length integer;
    v_etag           varchar;
BEGIN
    SELECT version, modified, content_length
    INTO v_version, v_modified, v_content_length
    FROM metadata
    WHERE id = p_metadata_id;

    SELECT title, content
    INTO v_title, v_content
    FROM documents
    WHERE metadata_id = p_metadata_id and version = v_version;

    if v_content_length is null then
        v_content_length := 0;
    end if;

    v_content_length := v_content_length + length(v_title) + length(v_content::text);

    v_etag := MD5(
        COALESCE(v_modified::text, 'null') || '-' ||
        COALESCE(v_content_length::text, '0')
    );

    UPDATE metadata
    SET etag = v_etag
    WHERE id = p_metadata_id;

    RETURN v_etag;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION calculate_metadata_guide_etag(p_metadata_id uuid) RETURNS varchar AS
$$
DECLARE
    v_version        integer;
    v_step_count     integer := 0;
    v_module_count   integer := 0;
    v_modified       timestamp;
    v_content_length integer;
    v_etag           varchar;
    v_title          varchar;
    v_content        jsonb;
BEGIN
    SELECT version, modified, content_length
    INTO v_version, v_modified, v_content_length
    FROM metadata
    WHERE id = p_metadata_id;

    SELECT COUNT(*)
    INTO v_step_count
    FROM guide_steps
    WHERE metadata_id = p_metadata_id
      and version = v_version;

    SELECT COUNT(*)
    INTO v_module_count
    FROM guide_step_modules
    WHERE metadata_id = p_metadata_id
      and version = v_version;

    SELECT title, content
    INTO v_title, v_content
    FROM documents
    WHERE metadata_id = p_metadata_id and version = v_version;

    if v_content_length is null then
        v_content_length := 0;
    end if;

    v_content_length := v_content_length + length(v_title) + length(v_content::text);

    FOR v_title, v_content IN SELECT title, content FROM documents as d INNER JOIN guide_steps as s on (d.metadata_id = s.step_metadata_id and d.version = s.step_metadata_version and s.metadata_id = p_metadata_id and s.version = v_version)
    LOOP
        v_content_length := v_content_length + length(v_title) + length(v_content::text);
    END LOOP;

    FOR v_title, v_content IN SELECT title, content FROM documents as d INNER JOIN guide_step_modules as s on (d.metadata_id = s.module_metadata_id and d.version = s.module_metadata_version and s.metadata_id = p_metadata_id and s.version = v_version)
    LOOP
        v_content_length := v_content_length + length(v_title) + length(v_content::text);
    END LOOP;

    v_etag := MD5(
        COALESCE(v_step_count::text, '0') || '-' ||
        COALESCE(v_module_count::text, '0') || '-' ||
        COALESCE(v_modified::text, 'null') || '-' ||
        COALESCE(v_content_length::text, '0')
    );

    UPDATE metadata
    SET etag = v_etag
    WHERE id = p_metadata_id;

    RETURN v_etag;
END;
$$ LANGUAGE plpgsql;
