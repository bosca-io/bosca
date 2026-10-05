update recommendations.contexts
set content_filter = '{"metadata":{"includedContentTypePrefixes":[],"excludedContentTypePrefixes":["image/","video/","audio/","font/","model/","application/octet-stream"],"includedAttributeTypes":[],"excludedAttributeTypes":[]},"collections":{"includedTypes":[],"excludedTypes":[],"includedAttributeTypes":[],"excludedAttributeTypes":[]}}'::jsonb,
    modified = now()
where type = 'default';
