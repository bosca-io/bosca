import assert from 'node:assert/strict';

export async function releaseEnvironmentRollback({ fixture, gql, reader, button, scope, dialog, expected, identity }) {
  const origin = process.env.ATLAS_WORKOPS_TEST_HELM_URL;
  assert.ok(origin && ['localhost', '127.0.0.1'].includes(new URL(origin).hostname), 'Use the local controller boundary fixture.');
  const cluster = 'b0000000-0000-0000-0000-000000000099';
  await gql('mutation($input:CommitFileInput!){git{commitFile(input:$input){commitSha}}}', { input: { repositoryId: fixture.repository, branch: 'main', path: '.bosca/deploy.yaml', content: `environments:\n  production:\n    target: helm\n    config:\n      clusterId: ${cluster}\n      releaseName: atlas-release-fixture\n      namespace: acceptance\n      repo: fixture\n      chart: fixture\n      chartVersion: 1.0.0\n      values: "fixture: true"\n`, message: 'Configure isolated rollback target', authorName: 'Atlas Release Tester', authorEmail: 'release-tester@example.test' } });
  const environment = fixture.environments.find(row => row.key === 'production').id;
  const state = () => gql('query($id:UUID!){workOps{crossProject{release(id:$id){environmentDeployments{id status versionId releaseId}}}}}', { id: fixture.release }).then(data => data.workOps.crossProject.release.environmentDeployments);
  const deploy = async () => {
    const value = (await gql('mutation($input:WorkOpsDeployInput!){workOps{multiRepo{deploy(input:$input){id version}}}}', { input: { environmentId: environment, projectId: fixture.project, versionId: fixture.version, releaseId: fixture.release, targetKind: 'HELM' } })).workOps.multiRepo.deploy;
    await gql('mutation($id:UUID!,$version:Long!){workOps{multiRepo{markDeployed(deploymentId:$id,expectedVersion:$version){id}}}}', value);
    return value.id;
  };
  const calls = async () => { const response = await fetch(origin + '/fixture/requests'); assert.equal(response.status, 200); return response.json(); };
  const deployment = await deploy();
  const environments = reader.locator('[data-workops-release-environments]');
  const environmentView = environments.locator(`[data-workops-selected-environment="${environment}"]`);
  await environments.getByRole('button', { name: 'Refresh environment state', exact: true }).click();
  await environments.locator('[data-bml-action-pending]').waitFor({ state: 'detached' });
  await environments.locator(`[data-workops-release-environment="${environment}"]`).click();
  await environmentView.locator(`[data-workops-environment-deployment="${deployment}"]`).waitFor();
  await fixture.programPermission('MANAGE', true); await fixture.repositoryPermission('EDIT', true);
  await button(reader, 'Refresh pipelines').click(); await scope(reader).locator('[data-bml-action-pending]').waitFor({ state: 'detached' });
  await button(reader, 'Roll back environment').click(); let panel = dialog('Roll back production', reader); await panel.waitFor();
  assert.equal(await panel.locator('input[name=revision]').inputValue(), '0'); await panel.locator('input[name=revision]').fill('7');
  await fixture.environmentPermission(false); await panel.getByRole('checkbox', { name: 'Confirm environment rollback', exact: true }).check(); expected(true);
  await panel.getByRole('button', { name: 'Roll back environment', exact: true }).click(); await panel.locator('[data-workops-release-ci-action-error]').waitFor(); expected(false);
  assert.equal(await panel.locator('input[name=revision]').inputValue(), '7'); assert.equal((await state()).find(row => row.id === deployment).status, 'DEPLOYED'); assert.equal((await calls()).length, 0);
  await fixture.environmentPermission(true); const fail = await fetch(origin + '/fixture/fail-next', { method: 'POST' }); assert.equal(fail.status, 200);
  await panel.getByRole('checkbox', { name: 'Confirm environment rollback', exact: true }).check(); expected(true);
  await panel.getByRole('button', { name: 'Roll back environment', exact: true }).click(); await panel.locator('[data-bml-action-pending]').waitFor({ state: 'detached' }); expected(false);
  await panel.locator('[data-workops-release-ci-action-error]').filter({ hasText: '503' }).waitFor();
  assert.equal(await panel.locator('input[name=revision]').inputValue(), '7'); assert.equal((await state()).find(row => row.id === deployment).status, 'DEPLOYED');
  await panel.getByRole('checkbox', { name: 'Confirm environment rollback', exact: true }).check(); await panel.getByRole('button', { name: 'Roll back environment', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  await scope(reader).getByText('atlas-release-fixture@43', { exact: true }).waitFor(); assert.equal((await state()).find(row => row.id === deployment).status, 'ROLLED_BACK');
  await environmentView.getByText('No current deployment targets are reported for the bundled projects.', { exact: true }).waitFor();
  assert.equal(await environmentView.locator(`[data-workops-environment-deployment="${deployment}"]`).count(), 0);
  let received = await calls(); assert.equal(received.length, 2);
  for (const call of received) { assert.equal(call.subject, identity); assert.equal(call.path, `/clusters/${cluster}/helm/rollback`); assert.deepEqual(call.request, { name: 'atlas-release-fixture', namespace: 'acceptance', toRevision: 7 }); }
  const previous = await deploy(); await button(reader, 'Roll back environment').click(); panel = dialog('Roll back production', reader); await panel.waitFor();
  assert.equal(await panel.locator('input[name=revision]').inputValue(), '0'); await panel.getByRole('checkbox', { name: 'Confirm environment rollback', exact: true }).check(); await panel.getByRole('button', { name: 'Roll back environment', exact: true }).click(); await panel.waitFor({ state: 'hidden' });
  assert.equal((await state()).find(row => row.id === previous).status, 'ROLLED_BACK'); received = await calls(); assert.equal(received.length, 3); assert.equal(received[2].request.toRevision, 0); assert.equal(received[2].subject, identity);
}
