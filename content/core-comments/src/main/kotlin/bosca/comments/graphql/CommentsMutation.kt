package bosca.comments.graphql

/**
 * Namespace marker for metadata comment mutations, exposed as
 * `MetadataMutation.comments`. Resolved by `CommentsMutationController` in the
 * comments implementation module; each field takes explicit `metadataId` /
 * `metadataVersion` arguments.
 */
object CommentsMutation
