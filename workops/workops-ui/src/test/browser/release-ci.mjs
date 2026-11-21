import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { launchTestBrowser } from '../../../../../gradle/bml-test-browser.mjs';
import { releaseCiFixture, until } from './release-ci-fixture.mjs';
import { releaseCiLayouts } from './release-ci-layouts.mjs';
import { releaseEnvironmentRollback } from './release-ci-environment.mjs';
import { releaseCiPolling } from './release-ci-polling.mjs';

const base = process.env.ATLAS_WORKOPS_TEST_URL, fixtureProject = process.env.ATLAS_WORKOPS_TEST_PROJECT;
assert.ok(base && ['localhost', '127.0.0.1'].includes(new URL(base).hostname) && fixtureProject, 'Use the isolated Atlas fixture.');
const reports = resolve('build/reports/release-ci-browser', new URL(base).port); await mkdir(reports, { recursive: true });
const owned = await launchTestBrowser(0); await owned.connection.contexts()[0].close();
const context = await owned.connection.newContext(), page = await context.newPage(), errors = [];
let expectedRejection = false, fixture, readerContext;
const observe = tab => {
  tab.on('pageerror', error => errors.push(error.message));
  tab.on('console', message => { if (message.type() === 'error' && !(expectedRejection && /500|bml: action/.test(message.text()))) errors.push(message.text()); });
}; observe(page);
const gql = async (query, variables = {}, tab = page, token = null) => {
  const result = await (tab ?? page).evaluate(async ({ query, variables, token }) => {
    const response = await fetch('/graphql', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token ?? await globalThis.boscaAuth.getToken()}` }, body: JSON.stringify({ query, variables }) });
    return response.json();
  }, { query, variables, token });
  assert.equal(result.errors, undefined, JSON.stringify(result)); return result.data;
};
const login = async (tab, path, reader = false) => {
  await tab.goto(base + path); await tab.locator('input[name=identifier]').fill(reader ? process.env.ATLAS_WORKOPS_TEST_READER_IDENTIFIER : 'admin');
  await tab.locator('input[name=password]').fill(reader ? process.env.ATLAS_WORKOPS_TEST_READER_PASSWORD : process.env.ATLAS_WORKOPS_TEST_PASSWORD);
  await tab.locator('button[type=submit]').click(); await tab.waitForURL(url => url.pathname === path.split('?')[0]);
};
const dialog = (name, tab) => tab.getByRole('dialog', { name, exact: true });
const scope = tab => tab.locator('[data-workops-release-ci]');
const button = (tab, name) => scope(tab).getByRole('button', { name, exact: true });
const refresh = async tab => { await button(tab, 'Refresh pipelines').click(); await scope(tab).locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); };
const choose = async (panel, name, value, tab) => { await panel.getByRole('combobox', { name, exact: true }).click(); await tab.getByRole('option', { name: value, exact: true }).click(); };
const control = async (tab, name) => {
  await button(tab, name).click(); const panel = dialog(name, tab); await panel.waitFor();
  await panel.getByRole('checkbox', { name: 'Confirm run action', exact: true }).check(); await panel.getByRole('button', { name, exact: true }).click(); await panel.waitFor({ state: 'hidden' });
};
const launch = async tab => {
  await button(tab, 'Start release').click(); const panel = dialog('Start release', tab); await panel.waitFor();
  await panel.getByRole('textbox', { name: /^message/ }).fill('Cancellation fixture');
  await panel.getByRole('button', { name: 'Start release', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  return (await fixture.runs())[0].runId;
};
try {
  await login(page, '/workops/releases'); fixture = await releaseCiFixture(gql, fixtureProject);
  const path = `/workops/releases/${fixture.release}`;
  await page.goto(base + path);
  try {
    await gql('mutation($id:UUID!){git{updateRepository(id:$id,input:{defaultBranch:"missing-release-branch"}){id}}}', { id: fixture.repository });
    await refresh(page);
    await scope(page).locator('[data-workops-release-ci-plans-error]').filter({ hasText: "Default branch 'missing-release-branch' cannot be resolved" }).waitFor();
    assert.equal(await button(page, 'Start release').isDisabled(), true);
  } finally {
    await gql('mutation($id:UUID!){git{updateRepository(id:$id,input:{defaultBranch:"main"}){id}}}', { id: fixture.repository });
  }
  await refresh(page);
  assert.equal(await scope(page).locator('[data-workops-release-ci-plans-error]').count(), 0);
  assert.equal(await button(page, 'Start release').isEnabled(), true);
  const links = (await gql('query($id:UUID!){workOps{projects{project(id:$id){repositories{id}}}}}', { id: fixture.project })).workOps.projects.project.repositories;
  try {
    await gql('mutation($project:UUID!,$id:UUID!){workOps{projects{removeProjectRepository(projectId:$project,id:$id)}}}', { project: fixture.project, id: links[0].id });
    await refresh(page);
    assert.equal(await scope(page).locator('[data-workops-release-ci-plans-error]').count(), 0);
    await scope(page).getByText('No release plans', { exact: true }).waitFor();
    assert.equal(await button(page, 'Start release').getAttribute('title'), 'Attach a Git repository to a bundled project before starting the release.');
  } finally {
    await gql('mutation($project:UUID!,$repository:UUID!){workOps{projects{addProjectRepository(projectId:$project,repositoryId:$repository){id}}}}', { project: fixture.project, repository: fixture.repository });
  }
  await refresh(page);
  assert.equal(await button(page, 'Start release').isEnabled(), true);
  await fixture.programPermission('VIEW', true); await fixture.repositoryPermission('VIEW', true);
  readerContext = await owned.connection.newContext(); const reader = await readerContext.newPage(); observe(reader);
  await login(reader, path, true); await reader.bringToFront();
  const identity = (await gql('{security{principals{current{id}}}}', {}, reader)).security.principals.current.id;
  assert.equal(await button(reader, 'Start release').isDisabled(), true); assert.match(await button(reader, 'Start release').getAttribute('title'), /Manage/);
  await fixture.programPermission('MANAGE', true); await refresh(reader); assert.match(await button(reader, 'Start release').getAttribute('title'), /Edit/);
  await fixture.repositoryPermission('EDIT', true); await refresh(reader); assert.match(await button(reader, 'Start release').getAttribute('title'), /Execute/);
  await fixture.repositoryPermission('EDIT', false); await fixture.repositoryPermission('EXECUTE', true); await refresh(reader); assert.match(await button(reader, 'Start release').getAttribute('title'), /Edit/);
  await fixture.repositoryPermission('EDIT', true); await refresh(reader); assert.equal(await button(reader, 'Start release').isEnabled(), true);
  assert.equal(await button(reader, 'Promote').isDisabled(), true);
  await button(reader, 'Start release').click(); let panel = dialog('Start release', reader); await panel.waitFor();
  assert.equal(await panel.getByRole('checkbox', { name: 'dryRun', exact: true }).isChecked(), false);
  assert.equal(Number(await panel.getByRole('spinbutton', { name: 'replicas', exact: true }).inputValue()), 0);
  await choose(panel, 'region', 'east', reader); await panel.getByRole('textbox', { name: /^message/ }).fill('Release <one> & keep');
  await fixture.repositoryPermission('EXECUTE', false); expectedRejection = true;
  await panel.getByRole('button', { name: 'Start release', exact: true }).click(); await panel.locator('[data-workops-release-ci-action-error]').waitFor(); expectedRejection = false;
  assert.deepEqual(await fixture.runs(), []); assert.equal(await panel.getByRole('textbox', { name: /^message/ }).inputValue(), 'Release <one> & keep');
  assert.equal(Number(await panel.getByRole('spinbutton', { name: 'replicas', exact: true }).inputValue()), 0);
  assert.equal(await panel.getByRole('combobox', { name: 'region', exact: true }).innerText(), 'east');
  await fixture.repositoryPermission('EXECUTE', true); await panel.getByRole('button', { name: 'Start release', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  const first = (await fixture.runs())[0].runId; let run = await fixture.run(first);
  assert.equal(run.triggeredBy, identity); assert.equal(run.commitSha, fixture.commit); assert.equal(run.triggerType, 'RELEASE');
  assert.equal(run.parameters['inputs.dryRun'], 'false'); assert.equal(Number(run.parameters['inputs.replicas']), 0); assert.equal(run.parameters['inputs.region'], 'east'); assert.equal(run.parameters['inputs.message'], 'Release <one> & keep');
  assert.deepEqual(run.jobs.map(job => job.name), ['prepare']);
  await scope(reader).getByRole('link', { name: 'Logs and details', exact: true }).waitFor(); assert.equal(await scope(reader).getByRole('link', { name: 'Logs and details', exact: true }).getAttribute('href'), `/pipelines/${first}`);
  await reader.goto(base + path);
  await reader.getByRole('button', { name: 'Edit release', exact: true }).click(); const editor = dialog('Edit release', reader); await editor.waitFor();
  await editor.locator('textarea[name=description]').fill('Unsent while native CI changes'); await editor.locator('textarea[name=description]').focus();
  await fixture.finish(first, 'FAILURE'); await scope(reader).getByText('Isolated compiler failure', { exact: true }).waitFor();
  assert.equal(await editor.locator('textarea[name=description]').inputValue(), 'Unsent while native CI changes');
  assert.equal(await editor.locator('textarea[name=description]').evaluate(node => node === document.activeElement), true);
  await editor.getByRole('link', { name: 'Done', exact: true }).click();
  await fixture.repositoryPermission('EDIT', false); await fixture.programPermission('MANAGE', false); await reader.goto(base + path);
  assert.equal(await button(reader, 'Rerun job').isEnabled(), true); assert.equal(await button(reader, 'Start release').isDisabled(), true);
  await control(reader, 'Rerun job'); run = await until('Second execution attempt', () => fixture.run(first), row => row.jobs[0].attempt === 2);
  assert.equal(run.triggeredBy, identity); await fixture.finish(first, 'SUCCESS'); await scope(reader).getByText('SUCCESS', { exact: true }).first().waitFor();
  const tag = await fixture.tag(); assert.equal(tag.isAnnotated, true); assert.equal(tag.targetSha, fixture.commit); assert.notEqual(tag.sha, fixture.commit);
  await fixture.programPermission('MANAGE', true); await fixture.repositoryPermission('EDIT', true); await refresh(reader);
  assert.equal(await button(reader, 'Promote').isDisabled(), true); assert.match(await button(reader, 'Promote').getAttribute('title'), /Execute permission on production/);
  await fixture.environmentPermission(true); await refresh(reader); assert.equal(await button(reader, 'Promote').isEnabled(), true);
  await button(reader, 'Promote').click(); panel = dialog('Promote to production', reader); await panel.waitFor();
  await panel.getByRole('checkbox', { name: 'phasedRelease', exact: true }).check(); await choose(panel, 'track', 'internal', reader); await panel.getByRole('checkbox', { name: 'Allow a version downgrade', exact: true }).check();
  await fixture.environmentPermission(false); expectedRejection = true; await panel.getByRole('button', { name: 'Start promotion', exact: true }).click(); await panel.locator('[data-workops-release-ci-action-error]').waitFor(); expectedRejection = false;
  assert.equal((await fixture.runs()).length, 1); assert.equal(await panel.getByRole('checkbox', { name: 'phasedRelease', exact: true }).isChecked(), true); assert.equal(await panel.getByRole('checkbox', { name: 'Allow a version downgrade', exact: true }).isChecked(), true);
  await fixture.environmentPermission(true); await panel.getByRole('button', { name: 'Start promotion', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  const promotion = (await fixture.runs())[0].runId; assert.notEqual(promotion, first); run = await fixture.run(promotion);
  assert.equal(run.triggeredBy, identity); assert.equal(run.ref, 'refs/tags/1.0.0'); assert.equal(run.commitSha, fixture.commit); assert.equal(run.parameters['inputs.phasedRelease'], 'true'); assert.equal(run.parameters['inputs.track'], 'internal'); assert.equal(run.parameters['promotion.allowDowngrade'], 'true');
  assert.deepEqual(run.jobs.map(job => job.name), ['approve-production']);
  await fixture.programPermission('MANAGE', false); await fixture.repositoryPermission('EDIT', false); await reader.goto(base + path);
  const approval = scope(reader).locator(`[data-workops-release-job="${run.jobs[0].id}"]`); await approval.getByText('Awaiting approval', { exact: true }).waitFor();
  assert.equal(await approval.getByRole('button', { name: 'Approve', exact: true }).isEnabled(), true);
  const comment = approval.locator('input[name=comment]'); await comment.fill(' Retain the release decision '); await comment.focus();
  await reader.waitForTimeout(4100); assert.equal(await comment.inputValue(), ' Retain the release decision '); assert.equal(await comment.evaluate(node => node === document.activeElement), true);
  await fixture.environmentPermission(false); expectedRejection = true; await approval.getByRole('button', { name: 'Reject', exact: true }).click(); await approval.locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); expectedRejection = false;
  assert.equal(await comment.inputValue(), ' Retain the release decision '); assert.equal((await fixture.run(promotion)).jobs[0].awaitingApproval, true);
  await fixture.environmentPermission(true); await approval.getByRole('button', { name: 'Reject', exact: true }).click();
  run = await until('Gate rejected', () => fixture.run(promotion), row => row.status === 'FAILURE'); assert.ok(run.jobs[0].errorMessage.endsWith(': Retain the release decision'));
  await button(reader, 'Rerun job').waitFor(); await control(reader, 'Rerun job'); await approval.getByRole('button', { name: 'Approve', exact: true }).waitFor();
  assert.equal((await fixture.run(promotion)).jobs[0].attempt, 2); assert.equal(await comment.inputValue(), ''); await comment.fill(' Approved in Atlas ');
  // Hold a real read so the approval must queue behind it instead of being dropped.
  const ciReadKey = await scope(reader).evaluate(node => node.closest('[data-bml-island]').getAttribute('data-bml-state-key'));
  let releaseRead, sawRead;
  const heldRead = new Promise(resolve => { releaseRead = resolve; }), startedRead = new Promise(resolve => { sawRead = resolve; });
  let held = false;
  const holdRefresh = async route => {
    const body = route.request().postDataJSON();
    if (!held && body?.method === 'refresh' && body?.stateKey === ciReadKey) { held = true; sawRead(); await heldRead; }
    await route.continue();
  };
  await reader.route('**/_bml/action', holdRefresh);
  try {
    await button(reader, 'Refresh pipelines').click(); await Promise.race([startedRead, reader.waitForTimeout(10000).then(() => { throw new Error('CI read was not intercepted'); })]);
    await approval.getByRole('button', { name: 'Approve', exact: true }).click();
    assert.equal((await fixture.run(promotion)).jobs[0].awaitingApproval, true); releaseRead();
    await until('Queued approval settled', () => fixture.run(promotion), row => row.status === 'SUCCESS');
  } finally { releaseRead(); await reader.unroute('**/_bml/action', holdRefresh); }
  run = await until('Gate approved', () => fixture.run(promotion), row => row.status === 'SUCCESS'); assert.equal(run.jobs[0].approvedBy, identity); assert.equal(run.jobs[0].approvalComment, 'Approved in Atlas');
  await releaseEnvironmentRollback({ fixture, gql, reader, button, scope, dialog, expected: value => { expectedRejection = value; }, identity });

  // Edit without Execute can cancel a queued job and its active run.
  await fixture.programPermission('MANAGE', true); await fixture.repositoryPermission('EDIT', true); await refresh(reader);
  const cancelJobRun = await launch(reader); await fixture.repositoryPermission('EXECUTE', false); await fixture.programPermission('MANAGE', false); await reader.goto(base + path);
  assert.equal(await button(reader, 'Cancel job').isEnabled(), true); await control(reader, 'Cancel job'); await until('Job cancellation settled', () => fixture.run(cancelJobRun), row => row.status === 'CANCELLED');
  await fixture.repositoryPermission('EXECUTE', true); await fixture.programPermission('MANAGE', true); await refresh(reader);
  const cancelRun = await launch(reader); await fixture.repositoryPermission('EXECUTE', false); await fixture.programPermission('MANAGE', false); await reader.goto(base + path);
  assert.equal(await button(reader, 'Cancel run').isEnabled(), true);
  await releaseCiPolling({ reader, base, path, scope, button, dialog, control }); await until('Run cancellation settled', () => fixture.run(cancelRun), row => row.status === 'CANCELLED');

  await fixture.repositoryPermission('EXECUTE', true); await fixture.programPermission('MANAGE', true); await reader.goto(base + path);
  await releaseCiLayouts({ page: reader, context: readerContext, base, path, reports, dialog, choose, button, scope });
  // Follow the actual native Git run destination.
  await scope(reader).getByRole('link', { name: 'Logs and details', exact: true }).click(); await reader.waitForURL(url => url.pathname === `/pipelines/${cancelRun}`);
  await reader.locator('.run-page').waitFor();
  const publication = (await gql('mutation($input:RegisterWorkOpsArtifactInput!){workOps{multiRepo{registerArtifact(input:$input){id version}}}}', { input: { projectId: fixture.project, versionId: fixture.version, artifactType: 'OTHER', coordinates: 'fixture:1.0.0' } })).workOps.multiRepo.registerArtifact;
  await gql('mutation($id:UUID!,$version:Long!){workOps{multiRepo{markArtifactPublished(id:$id,expectedVersion:$version){id}}}}', publication);
  await gql('mutation($release:UUID!,$project:UUID!,$version:UUID!){workOps{multiRepo{markProjectVersionDeployed(releaseId:$release,projectId:$project,versionId:$version){deploymentStatus}}}}', { release: fixture.release, project: fixture.project, version: fixture.version });
  await reader.goto(base + path); await button(reader, 'Roll back attempt').click(); panel = dialog('Roll back release attempt', reader); await panel.waitFor();
  await fixture.repositoryPermission('EDIT', false); await fixture.repositoryPermission('EXECUTE', false);
  await panel.getByRole('checkbox', { name: 'Confirm attempt rollback', exact: true }).check(); await panel.getByRole('button', { name: 'Roll back attempt', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  await scope(reader).getByText('Release attempt rolled back.', { exact: true }).waitFor();
  const tags = (await gql('query($id:UUID!){git{tags(repositoryId:$id){name}}}', { id: fixture.repository })).git.tags; assert.equal(tags.some(tag => tag.name === '1.0.0'), false);
  assert.deepEqual((await gql('query($id:UUID!){workOps{multiRepo{artifacts(versionId:$id){id}}}}', { id: fixture.version })).workOps.multiRepo.artifacts, []);
  assert.equal((await gql('query($id:UUID!){workOps{crossProject{versionsForRelease(releaseId:$id){deploymentStatus}}}}', { id: fixture.release })).workOps.crossProject.versionsForRelease[0].deploymentStatus, 'PENDING');
  assert.equal((await fixture.runs()).length, 4);
  await button(reader, 'Patch release').click(); panel = dialog('Create patch release', reader); await panel.waitFor();
  await choose(panel, /^Affected projects/, 'RCIAPP · Native release application', reader); await reader.keyboard.press('Escape');
  await panel.getByRole('button', { name: 'Create patch release', exact: true }).click(); await reader.waitForURL(url => url.pathname.startsWith('/workops/releases/') && url.pathname !== path);
  const patch = new URL(reader.url()).pathname.split('/').at(-1); const bundle = (await gql('query($id:UUID!){workOps{crossProject{versionsForRelease(releaseId:$id){projectId version{id name}}}}}', { id: patch })).workOps.crossProject.versionsForRelease;
  assert.equal(bundle.length, 1); assert.equal(bundle[0].projectId, fixture.project); assert.equal(bundle[0].version.name, '1.0.1'); assert.deepEqual(errors, []);
  console.log('Atlas native release CI passed actual YAML synchronization, caller-attributed false/zero inputs, independent roles, denied draft retries, agent failure/success, job rerun/cancel, run cancel, promotion source/approval gates, retained approval comments, attempt rollback, patch identity and layouts.');
} catch (error) {
  for (const tab of owned.connection.contexts().flatMap(context => context.pages())) {
    await tab.screenshot({ path: resolve(reports, `failure-${tab === page ? 'admin' : 'reader'}.png`), fullPage: true }).catch(() => {});
    console.error('CI browser state', JSON.stringify(await tab.evaluate(() => ({ path: location.pathname, text: document.querySelector('[data-workops-release-ci]')?.innerText.slice(0, 7000), dialogs: [...document.querySelectorAll('dialog[open]')].map(node => node.innerText.slice(0, 2000)) })).catch(String)));
  }
  throw error;
} finally {
  if (readerContext) await readerContext.close();
  try { if (fixture) await fixture.restore(); } finally { await context.close(); await owned.close(); }
}
