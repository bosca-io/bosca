import { cpSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Stage a registry package while retaining file dependencies for workspace development.
const [version, destination] = process.argv.slice(2);
if (!version || !destination || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.test(version)) {
  throw new Error('Usage: node scripts/release/prepare-bml-auth-ui.mjs <release-version> <empty-directory>');
}
const source = fileURLToPath(new URL('../../bml/bml-auth-ui/', import.meta.url));
const manifest = JSON.parse(readFileSync(resolve(source, 'package.json'), 'utf8'));
manifest.version = version;
manifest.publishConfig = { ...manifest.publishConfig, access: 'public' };
delete manifest.private;
delete manifest.devDependencies;
for (const name of ['@bosca/auth-client-browser', '@bosca/bml']) {
  if (!manifest.dependencies[name]) throw new Error(`Missing BML dependency: ${name}`);
  manifest.dependencies[name] = version;
}
for (const [name, value] of Object.entries(manifest.dependencies)) {
  if (/^(file:|link:|workspace:|catalog:)/.test(value)) {
    throw new Error(`Unresolved local dependency: ${name}=${value}`);
  }
}
mkdirSync(destination, { recursive: false });
for (const path of [...manifest.files, 'README.md']) {
  cpSync(resolve(source, path), resolve(destination, path), { recursive: true });
}
writeFileSync(resolve(destination, 'package.json'), JSON.stringify(manifest, null, 2) + '\n');
