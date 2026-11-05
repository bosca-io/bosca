import { createInterface } from 'node:readline';
for await (const line of createInterface({ input: process.stdin })) {
  const request = JSON.parse(line);
  if (!('id' in request)) continue;
  let result;
  if (request.method === 'initialize') result = { protocolVersion: request.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'fixture', version: '1' } };
  else if (request.method === 'tools/list') {
    if (process.env.REQUIRED_SECRET !== 'fixture-external-secret') {
      process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, error: { code: -32000, message: 'Denied' } }) + '\n'); continue;
    }
    result = { tools: [{ name: 'echo', description: 'Fixture', inputSchema: { type: 'object' } }] };
  } else result = { content: [{ type: 'text', text: 'fixture' }] };
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) + '\n');
}
