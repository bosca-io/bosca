import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const prepare = resolve(root, 'scripts/release/prepare-bml-auth-ui.mjs');
const read = path => JSON.parse(readFileSync(path, 'utf8'));

function pack(directory, temporary) {
  return JSON.parse(execFileSync('npm', ['pack', directory, '--json', '--ignore-scripts',
    '--cache', resolve(temporary, 'cache'), '--pack-destination', temporary], { encoding: 'utf8' }))[0];
}

test('authentication UI tarball has release dependencies and complete client exports', () => {
  const temporary = mkdtempSync(resolve(tmpdir(), 'bml-package-test-'));
  const source = resolve(root, 'bml/bml-auth-ui/package.json');
  const original = readFileSync(source, 'utf8');
  try {
    const stage = resolve(temporary, 'auth-ui');
    execFileSync(process.execPath, [prepare, '1.2.3-rc.1', stage]);
    const archive = pack(stage, temporary);
    assert.equal(archive.name, '@bosca/bml-auth-ui');
    assert.equal(archive.version, '1.2.3-rc.1');
    const packed = JSON.parse(execFileSync('tar', ['-xOf', resolve(temporary, archive.filename), 'package/package.json'], { encoding: 'utf8' }));
    assert.equal(packed.private, undefined);
    assert.equal(packed.publishConfig.access, 'public');
    assert.deepEqual(packed.dependencies, {
      '@bosca/auth-client-browser': '1.2.3-rc.1', '@bosca/bml': '1.2.3-rc.1',
    });
    const files = new Set(archive.files.map(file => file.path));
    for (const entry of Object.values(packed.exports)) assert.ok(files.has(entry.replace(/^\.\//, '')), entry);
    assert.ok(files.has('src/main/client/auth.ts'));
    assert.ok(![...files].some(file => file.includes('node_modules') || file.includes('/test/')));
    assert.equal(readFileSync(source, 'utf8'), original);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
});

test('runtime tarball ships the bundler, all helper modules and TypeScript runtime', () => {
  const temporary = mkdtempSync(resolve(tmpdir(), 'bml-package-test-'));
  try {
    const archive = pack(resolve(root, 'bml/bml-runtime'), temporary);
    const files = new Set(archive.files.map(file => file.path));
    for (const file of ['src/index.ts', 'tools/bundle.mjs', 'tools/library.mjs', 'tools/dependencies.mjs',
      'tools/css-assets.mjs', 'tools/module-urls.mjs']) assert.ok(files.has(file), file);
    const manifest = read(resolve(root, 'bml/bml-runtime/package.json'));
    assert.ok(manifest.dependencies.esbuild);
    assert.ok(manifest.dependencies.acorn);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
});

test('staging rejects invalid versions before creating a package', () => {
  const temporary = mkdtempSync(resolve(tmpdir(), 'bml-package-test-'));
  try {
    const stage = resolve(temporary, 'invalid');
    assert.throws(() => execFileSync(process.execPath, [prepare, 'refs/tags/1.2.3', stage], { stdio: 'pipe' }));
    assert.equal(existsSync(stage), false);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
});
