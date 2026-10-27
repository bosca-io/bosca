import { spawnSync } from 'node:child_process';
import { constants } from 'node:fs';
import { access, mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname } from 'node:path';

const output = process.argv[2];
if (!output) throw new Error('Pass the browser executable output file.');

function run(executable, args) {
  const result = spawnSync(executable, args, { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${executable} ${args.join(' ')} failed (${result.signal ?? result.status}).`);
}

run('npm', ['ci', '--include=dev', '--no-audit', '--no-fund']);
run(process.execPath, ['node_modules/playwright-core/cli.js', 'install', '--no-shell', 'chromium']);
const { chromium } = createRequire(import.meta.url)('playwright-core');
const executable = chromium.executablePath();
await access(executable, constants.X_OK);
run(executable, ['--version']);
await mkdir(dirname(output), { recursive: true });
await writeFile(output, `${executable}\n`);
