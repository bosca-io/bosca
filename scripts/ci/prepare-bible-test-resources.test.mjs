import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

// Stored ZIP with metadata.xml, USX_1/styles.xml and USX_1/GEN.usx.
const bundle = Buffer.from('UEsDBBQAAAAAAAAAIVxPxc8cDgAAAA4AAAAMAAAAbWV0YWRhdGEueG1sPERCTE1ldGFkYXRhLz5QSwMEFAAAAAAAAAAhXJh3Ke0JAAAACQAAABAAAABVU1hfMS9zdHlsZXMueG1sPHN0eWxlcy8+UEsDBBQAAAAAAAAAIVyWGl0DBgAAAAYAAAANAAAAVVNYXzEvR0VOLnVzeDx1c3gvPlBLAQIUAxQAAAAAAAAAIVxPxc8cDgAAAA4AAAAMAAAAAAAAAAAAAACAAQAAAABtZXRhZGF0YS54bWxQSwECFAMUAAAAAAAAACFcmHcp7QkAAAAJAAAAEAAAAAAAAAAAAAAAgAE4AAAAVVNYXzEvc3R5bGVzLnhtbFBLAQIUAxQAAAAAAAAAIVyWGl0DBgAAAAYAAAANAAAAAAAAAAAAAACAAW8AAABVU1hfMS9HRU4udXN4UEsFBgAAAAADAAMAswAAAKAAAAAAAA==', 'base64');

async function fixture(t, status = 200, body = bundle) {
  const root = await mkdtemp(join(tmpdir(), 'bosca-ci-bundles-'));
  const scripts = join(root, 'scripts/ci');
  await mkdir(scripts, { recursive: true });
  const script = join(scripts, 'prepare-bible-test-resources.sh');
  await copyFile(new URL('./prepare-bible-test-resources.sh', import.meta.url), script);
  const requests = [];
  const server = createServer((request, response) => {
    requests.push({ url: request.url, authorization: request.headers.authorization });
    response.writeHead(request.headers.authorization === 'Bearer fixture-token' ? status : 401);
    response.end(body);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    server.close();
    await once(server, 'close');
    await rm(root, { recursive: true, force: true });
  });
  const url = `http://127.0.0.1:${server.address().port}/raw/bibles/bibles/0.0.1`;
  async function run(overrides = {}) {
    const env = { ...process.env, BIBLE_RAW_ARTIFACTS: url, BOSCA_REGISTRY_URL: '', BOSCA_REGISTRY_TOKEN: 'fixture-token', ...overrides };
    const child = spawn('bash', [script], { env });
    let output = '';
    child.stdout.on('data', data => { output += data; });
    child.stderr.on('data', data => { output += data; });
    const [code] = await once(child, 'close');
    assert.ok(!output.includes('fixture-token'), 'credentials must stay out of diagnostics');
    return { code, output };
  }
  return { run, requests, registryUrl: new URL(url).origin, destination: join(root, 'content/bible-compiler/.test-resources') };
}

test('fetches both bundles using registry authentication', async t => {
  const f = await fixture(t);
  const result = await f.run();
  assert.equal(result.code, 0, result.output);
  for (const name of ['kjv', 'asv']) {
    const path = `/raw/bibles/bibles/0.0.1/${name}.zip`;
    assert.deepEqual(await readFile(join(f.destination, `${name}.zip`)), bundle);
    assert.ok(f.requests.some(r => r.url === path && r.authorization === 'Bearer fixture-token'));
  }
  assert.deepEqual((await readdir(f.destination)).sort(), ['asv.zip', 'kjv.zip']);
});

test('failed downloads preserve existing bundles and remove partial files', async t => {
  const f = await fixture(t, 503);
  await mkdir(f.destination, { recursive: true });
  await writeFile(join(f.destination, 'kjv.zip'), 'existing bundle');
  const result = await f.run();
  assert.notEqual(result.code, 0);
  assert.equal(await readFile(join(f.destination, 'kjv.zip'), 'utf8'), 'existing bundle');
  assert.deepEqual(await readdir(f.destination), ['kjv.zip']);
});

test('uses the configured registry for the pinned Bible test bundles', async t => {
  const f = await fixture(t);
  const result = await f.run({ BIBLE_RAW_ARTIFACTS: '', BOSCA_REGISTRY_URL: `${f.registryUrl}/` });
  assert.equal(result.code, 0, result.output);
  assert.deepEqual(f.requests.map(r => r.url), [
    '/raw/bibles/bibles/0.0.1/kjv.zip', '/raw/bibles/bibles/0.0.1/asv.zip',
  ]);
});

test('rejects HTTP-success HTML without replacing a valid bundle', async t => {
  const f = await fixture(t, 200, '<html><title>Studio</title></html>');
  await mkdir(f.destination, { recursive: true });
  await writeFile(join(f.destination, 'kjv.zip'), bundle);
  const result = await f.run();
  assert.notEqual(result.code, 0);
  assert.match(result.output, /not a valid ZIP bundle/);
  assert.deepEqual(await readFile(join(f.destination, 'kjv.zip')), bundle);
  assert.deepEqual(await readdir(f.destination), ['kjv.zip']);
});

test('requires a registry or bundle URL and a token before fetching', async t => {
  const f = await fixture(t);
  for (const overrides of [{ BIBLE_RAW_ARTIFACTS: '' }, { BOSCA_REGISTRY_TOKEN: '' }]) {
    assert.notEqual((await f.run(overrides)).code, 0);
  }
  assert.equal(f.requests.length, 0);
});
