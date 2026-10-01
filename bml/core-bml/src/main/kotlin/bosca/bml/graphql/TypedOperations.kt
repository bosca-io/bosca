package bosca.bml.graphql

import bosca.graphql.client.BoscaOperation

/**
 * Executes a generated typed operation (`io.bosca.graphql`'s codegen output) over the BML
 * data-plane client — the bridge every site's server scripts use: encode the operation's
 * variables, post the document through `ctx.gql` (token passthrough), decode the data.
 */
public suspend fun <V, D> GraphQLClient.execute(op: BoscaOperation<V, D>, variables: V): D =
    op.decodeData(execute(op.document, op.encodeVariables(variables), op.operationName))
