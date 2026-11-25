// Native controller entrypoint, AES-GCM and Unix Docker transport. No real Docker or Bosca writes.
import assert from 'node:assert/strict';
import { randomUUID, randomBytes, createHash, createCipheriv } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const binary = resolve(process.argv[2]);
const root = await mkdtemp(join(tmpdir(), 'swarm-native-'));
const token = randomUUID() + randomUUID(), key = 'isolated-native-controller-key';
const id = randomUUID(), session = randomUUID();
const socket = join(root, 'docker.sock');
let cancellation, requests = [], child;
const docker = createServer(async (request, response) => {
  const parts = []; for await (const part of request) parts.push(part);
  const body = Buffer.concat(parts).toString();
  requests.push({ method: request.method, path: request.url, body });
  const path = request.url.replace('/v1.49', '').split('?')[0];
  const send = (code, value) => response.writeHead(code, { 'Content-Type': 'application/json' }).end(JSON.stringify(value));
  if (request.method === 'POST' && path === '/configs/create') {
    cancellation = { ID: 'fixture-cancel-id', Spec: JSON.parse(body) };
    send(201, { ID: cancellation.ID });
  } else if (path === `/configs/bosca-kit-${id}-stop`) send(cancellation ? 200 : 404, cancellation ?? {});
  else if (path === '/configs') send(200, cancellation ? [cancellation] : []);
  else if (['/tasks', '/services', '/secrets'].includes(path)) send(200, []);
  else send(404, { message: 'fixture resource missing' });
});
const reservation = createServer();
try {
  await new Promise(resolve => docker.listen(socket, resolve));
  await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const config = join(root, 'controller.json');
  await writeFile(config, JSON.stringify({ profiles: {}, socket }));
  child = spawn(binary, ['swarm', 'runtime-controller', '--config', config, '--host', '127.0.0.1', '--port', String(port)], {
    env: { ...process.env, BOSCA_SWARM_CONTROLLER_TOKEN: token, SECURITY_ENCRYPTION_KEY: key, BOSCA_NO_UPDATE_CHECK: '1' }, stdio: ['ignore', 'ignore', 'pipe']
  });
  let errors = ''; child.stderr.on('data', value => errors = (errors + value).slice(-8000));
  const base = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 15000;
  while (true) {
    try { if ((await fetch(base + '/healthz')).status === 200) break; } catch {}
    assert(child.exitCode === null && Date.now() < deadline, errors || 'Native controller did not become healthy');
    await delay(50);
  }
  assert.equal((await fetch(`${base}/v1/runtimes/${id}`)).status, 401);
  const values = { BOSCA_TOKEN: 'bsk_private_native_fixture', BOSCA_RUNTIME_TOKEN: 'private-native-control-fixture', BOSCA_RUNTIME_EXPIRES: String(Math.floor(Date.now() / 1000) + 600) };
  const nonce = randomBytes(12), cipher = createCipheriv('aes-256-gcm', createHash('sha256').update(key).digest(), nonce);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(values)), cipher.final(), cipher.getAuthTag()]);
  const request = { profile: 'fixture', key: `kit:${session}:1`, image: 'fixture', environment: {}, secrets: {
    keyId: session, nonce: nonce.toString('base64'), ciphertext: ciphertext.toString('base64'), names: Object.keys(values)
  } };
  const response = await fetch(`${base}/v1/runtimes/${id}/stop`, { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body: JSON.stringify(request) });
  assert.equal(response.status, 200, errors + await response.clone().text());
  assert.equal(await response.json(), true);
  assert.equal(cancellation.Spec.Labels['bosca.kit.runtime'], id);
  assert.equal(cancellation.Spec.Labels['bosca.kit.expires'], values.BOSCA_RUNTIME_EXPIRES);
  assert(requests.some(request => request.method === 'POST' && request.path === '/v1.49/configs/create'));
  assert(!JSON.stringify(requests).includes(values.BOSCA_TOKEN));
  console.log('Native Swarm controller authentication, decryption and Unix transport passed');
} finally {
  if (child?.exitCode === null) {
    child.kill('SIGTERM');
    const deadline = Date.now() + 3000;
    while (child.exitCode === null && child.signalCode === null && Date.now() < deadline) await delay(20);
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  }
  docker.closeAllConnections(); await new Promise(resolve => docker.close(resolve));
  await rm(root, { recursive: true, force: true });
}
