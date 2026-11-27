import { compactResult } from './presentation-checks.mjs';
import assert from 'node:assert/strict';

/** Verify project relationships, human Git setup and independent telemetry through the owning APIs. */
export async function projectRelations({ page, base, project, href, program, destination, owner, gql, choose, dialog, rejection, owned, login, observe, permission, privateOwner }) {
  const createProject = async (key, name) => (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { programId: program, ownerProfileId: owner, key, name } })).workOps.projects.create.id;
  const provider = await createProject('DEPPROV', 'Dependency provider'), consumer = await createProject('DEPCONS', 'Dependency consumer');
  const dependencies = async () => (await gql('query($id:UUID!){workOps{multiRepo{dependencies(projectId:$id){id providerProjectId providerVersionConstraint dependencyType} consumers(projectId:$id){id consumerProjectId}}}}', { id: project })).workOps.multiRepo;
  await page.goto(`${base}${href}?tab=dependencies`); await page.getByRole('button', { name: 'Declare dependency', exact: true }).click(); const declaring = dialog('Declare dependency'); await declaring.waitFor();
  await choose(declaring, 'Provider project', 'DEPPROV · Dependency provider'); await choose(declaring, 'Type', 'Runtime'); await declaring.locator('input[name=constraint]').fill('6.0.*');
  await declaring.getByRole('button', { name: 'Declare dependency', exact: true }).click(); await declaring.waitFor({ state: 'hidden' });
  let relation = (await dependencies()).dependencies.find(row => row.providerProjectId === provider); assert.ok(relation); assert.equal(relation.dependencyType, 'RUNTIME'); assert.equal(relation.providerVersionConstraint, '6.0.*');
  await gql('mutation($input:CreateWorkOpsDependencyDeclarationInput!){workOps{multiRepo{declareDependency(input:$input){id}}}}', { input: { consumerProjectId: consumer, providerProjectId: project, providerVersionConstraint: 'match', dependencyType: 'BUILD' } });
  await page.getByRole('button', { name: 'Refresh dependencies', exact: true }).click(); await page.getByRole('region', { name: 'Depended on by', exact: true }).getByText('DEPCONS · Dependency consumer', { exact: true }).waitFor();
  const dependencyRow = page.locator(`[data-workops-dependency="${relation.id}"]`); await dependencyRow.getByRole('button', { name: 'Remove dependency on DEPPROV · Dependency provider', exact: true }).click();
  const removing = dialog('Remove dependency'); await removing.waitFor(); await removing.getByRole('button', { name: 'Cancel', exact: true }).click(); await removing.waitFor({ state: 'hidden' }); assert.ok((await dependencies()).dependencies.some(row => row.id === relation.id));
  await dependencyRow.getByRole('button', { name: 'Remove dependency on DEPPROV · Dependency provider', exact: true }).click(); await removing.waitFor(); await removing.getByRole('checkbox', { name: 'Confirm removal', exact: true }).check();
  await removing.getByRole('button', { name: 'Remove dependency', exact: true }).click(); await removing.waitFor({ state: 'hidden' }); await dependencyRow.waitFor({ state: 'detached' });
  assert.equal((await dependencies()).dependencies.some(row => row.id === relation.id), false);
  relation = (await gql('mutation($input:CreateWorkOpsDependencyDeclarationInput!){workOps{multiRepo{declareDependency(input:$input){id}}}}', { input: { consumerProjectId: project, providerProjectId: provider, providerVersionConstraint: '*', dependencyType: 'CONTRACT' } })).workOps.multiRepo.declareDependency;

  const createRepository = async (slug, name) => (await gql('mutation($input:CreateGitRepositoryInput!){git{createRepository(input:$input){id}}}', { input: { ownerId: owner, slug, name, visibility: 'PRIVATE', defaultBranch: 'main', initializeWithReadme: false } })).git.createRepository.id;
  const repository = await createRepository('project-detail-config', 'Project configuration'), readerRepository = await createRepository('reader-project-config', 'Reader configuration'), changedRepository = await createRepository('changed-project-config', 'Changed configuration');
  const repositories = async () => (await gql('query($id:UUID!){workOps{projects{project(id:$id){repositories{id repositoryId}}}}}', { id: project })).workOps.projects.project.repositories;
  const blob = async id => (await gql('query($id:UUID!){git{blob(repositoryId:$id,ref:"main",path:".bosca/deploy.yaml"){content}}}', { id })).git.blob;
  const head = async id => (await gql('query($id:UUID!){git{commits(repositoryId:$id,ref:"main",limit:1){sha authorName authorEmail committerName committerEmail}}}', { id })).git.commits[0];
  await page.goto(`${base}${href}?tab=repositories`); const repoScope = page.locator('[data-workops-project-repositories]');
  await choose(repoScope, 'Repository', 'Project configuration'); await repoScope.getByRole('button', { name: 'Attach repository', exact: true }).click();
  const attached = repoScope.getByRole('link', { name: 'Show the attached repository', exact: true }); await attached.waitFor(); await compactResult(attached); const association = new URL(await attached.getAttribute('href'), base).searchParams.get('item'); assert.ok(association);
  assert.ok((await repositories()).some(row => row.id === association && row.repositoryId === repository));
  const row = page.locator(`[data-workops-project-repository="${association}"]`); await row.getByRole('button', { name: 'Create deploy config for Project configuration', exact: true }).click(); const setup = dialog('Create deploy config'); await setup.waitFor();
  await setup.locator('input[name=authorName]').fill('Project Human'); await setup.locator('input[name=authorEmail]').fill('project.human@example.test');
  rejection(true); await setup.getByRole('button', { name: 'Create and commit deploy config', exact: true }).click(); await setup.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
  assert.equal(await setup.locator('input[name=authorName]').inputValue(), 'Project Human'); assert.equal(await setup.locator('input[name=authorEmail]').inputValue(), 'project.human@example.test');
  const environmentType = (await gql('mutation{workOps{multiRepo{createEnvironmentType(name:"project-browser"){id}}}}')).workOps.multiRepo.createEnvironmentType.id;
  for (const programId of [program, destination]) await gql('mutation($input:CreateWorkOpsEnvironmentInput!){workOps{multiRepo{createEnvironment(input:$input){id}}}}', { input: { programId, typeId: environmentType, key: 'testing', name: 'Testing', targetType: 'GENERIC', ephemeral: false } });
  await setup.getByRole('button', { name: 'Create and commit deploy config', exact: true }).click(); await setup.waitFor({ state: 'hidden' }); const generated = dialog('Committed deploy config'); await generated.waitFor();
  await generated.locator('[data-bml-source-view][data-path=".bosca/deploy.yaml"]').waitFor();
  assert.match((await blob(repository)).content, /environments:\s+testing:/); let commit = await head(repository);
  assert.equal(commit.authorName, 'Project Human'); assert.equal(commit.committerName, 'Project Human'); assert.equal(commit.authorEmail, 'project.human@example.test'); assert.equal(commit.committerEmail, 'project.human@example.test');
  await generated.getByRole('button', { name: 'Done', exact: true }).click(); await generated.waitFor({ state: 'hidden' });
  await row.getByRole('button', { name: 'Create deploy config for Project configuration', exact: true }).click(); await setup.waitFor();
  rejection(true); await setup.getByRole('button', { name: 'Create and commit deploy config', exact: true }).click(); await setup.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
  assert.equal((await head(repository)).sha, commit.sha); await setup.getByRole('button', { name: 'Cancel', exact: true }).click(); await setup.waitFor({ state: 'hidden' });
  const attachApi = async id => (await gql('mutation($project:UUID!,$repository:UUID!){workOps{projects{addProjectRepository(projectId:$project,repositoryId:$repository){id}}}}', { project, repository: id })).workOps.projects.addProjectRepository.id;
  const readerAssociation = await attachApi(readerRepository), changedAssociation = await attachApi(changedRepository);
  await page.goto(`${base}${href}?tab=repositories`);
  await page.locator(`[data-workops-project-repository="${changedAssociation}"]`).getByRole('button', { name: 'Create deploy config for Changed configuration', exact: true }).click(); await setup.waitFor();
  await setup.locator('input[name=authorName]').fill('Project Human'); await setup.locator('input[name=authorEmail]').fill('project.human@example.test');
  await gql('mutation($project:UUID!,$id:UUID!){workOps{projects{removeProjectRepository(projectId:$project,id:$id)}}}', { project, id: changedAssociation });
  await setup.getByRole('button', { name: 'Create and commit deploy config', exact: true }).click(); await setup.getByText('The repository association changed. Reload it before creating a deploy config.', { exact: true }).waitFor();
  assert.equal(await blob(changedRepository), null); await setup.getByRole('button', { name: 'Cancel', exact: true }).click(); await setup.waitFor({ state: 'hidden' });
  await page.goto(`${base}${href}?tab=repositories`); await page.locator(`[data-workops-project-repository="${association}"]`).getByRole('button', { name: 'Remove association with Project configuration', exact: true }).click();
  const unlink = dialog('Remove repository association'); await unlink.waitFor(); await unlink.getByRole('checkbox', { name: 'Confirm removal', exact: true }).check(); await unlink.getByRole('button', { name: 'Remove association', exact: true }).click(); await unlink.waitFor({ state: 'hidden' });
  await page.locator(`[data-workops-project-repository="${association}"]`).waitFor({ state: 'detached' }); assert.equal((await repositories()).some(item => item.repositoryId === repository), false); assert.ok((await blob(repository)).content);
  await attachApi(repository);

  await page.goto(`${base}${href}?tab=analytics`); const applications = page.locator('[data-workops-project-telemetry="applications"]'), services = page.locator('[data-workops-project-telemetry="services"]');
  await applications.locator('input[name=value]').fill('mobile.app'); await services.locator('input[name=value]').fill('unsent.service');
  await applications.getByRole('button', { name: 'Add identifier', exact: true }).click(); await applications.getByText('Identifier added.', { exact: true }).waitFor(); assert.equal(await services.locator('input[name=value]').inputValue(), 'unsent.service');
  await services.getByRole('button', { name: 'Add identifier', exact: true }).click(); await services.getByText('Identifier added.', { exact: true }).waitFor();
  const telemetry = async () => (await gql('query($id:UUID!){workOps{projects{project(id:$id){analyticsApplications{id applicationId} analyticsServices{id service}}}}}', { id: project })).workOps.projects.project;
  let identifiers = await telemetry(); assert.ok(identifiers.analyticsApplications.some(item => item.applicationId === 'mobile.app')); assert.ok(identifiers.analyticsServices.some(item => item.service === 'unsent.service'));
  for (const [scope, value] of [[applications, 'mobile.app'], [services, 'unsent.service']]) {
    await scope.getByRole('button', { name: `Remove ${value}`, exact: true }).click(); const confirmation = scope.getByRole('dialog', { name: 'Remove identifier', exact: true }); await confirmation.waitFor();
    await confirmation.getByRole('checkbox', { name: 'Confirm removal', exact: true }).check(); await confirmation.getByRole('button', { name: 'Remove identifier', exact: true }).click(); await confirmation.waitFor({ state: 'hidden' });
    await scope.getByText('No identifiers configured.', { exact: true }).waitFor();
  }
  for (let index = 0; index < 26; index++) {
    await gql('mutation($id:UUID!,$value:String!){workOps{projects{addAnalyticsApplication(projectId:$id,applicationId:$value){id}}}}', { id: project, value: `paged.app.${index}` });
    await gql('mutation($id:UUID!,$value:String!){workOps{projects{addAnalyticsService(projectId:$id,service:$value){id}}}}', { id: project, value: `paged.service.${index}` });
  }
  await page.goto(`${base}${href}?tab=analytics`);
  await applications.getByRole('navigation', { name: 'Analytics applications pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click();
  await page.waitForURL(url => url.searchParams.get('applicationOffset') === '25'); assert.equal(await applications.locator('[data-workops-project-identifier]').count(), 1);
  await services.getByRole('navigation', { name: 'Analytics services pages', exact: true }).getByRole('link', { name: 'Next', exact: true }).click();
  await page.waitForURL(url => url.searchParams.get('serviceOffset') === '25'); assert.equal(new URL(page.url()).searchParams.get('applicationOffset'), '25'); assert.equal(await applications.locator('[data-workops-project-identifier]').count(), 1); assert.equal(await services.locator('[data-workops-project-identifier]').count(), 1);

  for (const action of ['VIEW', 'EDIT', 'MANAGE']) await permission(project, action, true);
  const readerContext = await owned.connection.newContext(), reader = await readerContext.newPage(); observe(reader);
  try {
    await login(reader, href, true); await reader.getByText('Profile unavailable', { exact: true }).waitFor(); await reader.getByText('Program unavailable', { exact: true }).waitFor();
    const access = await gql('query($id:UUID!,$profile:UUID!){workOps{projects{project(id:$id){owner{id}}}} profiles{profile(id:$profile){id}} git{repositories(includeArchived:true){id}}}', { id: project, profile: privateOwner }, reader);
    assert.equal(access.workOps.projects.project.owner, null); assert.equal(access.profiles.profile, null); assert.equal(access.git.repositories.some(item => item.id === readerRepository), false);
    assert.equal((await reader.locator('[data-workops-project-details]').innerText()).includes('Private detail owner'), false);
    await reader.goto(`${base}${href}?tab=dependencies`); await reader.locator(`[data-workops-dependency="${relation.id}"]`).getByText('Project unavailable', { exact: true }).waitFor();
    await reader.goto(`${base}${href}?tab=labels`); await reader.getByRole('button', { name: 'New label', exact: true }).click(); const readerLabel = dialog('New label', reader); await readerLabel.waitFor(); await readerLabel.locator('input[name=name]').fill('reader-label');
    await permission(project, 'MANAGE', false); await permission(project, 'EDIT', false); rejection(true);
    await readerLabel.getByRole('button', { name: 'Create label', exact: true }).click(); await readerLabel.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
    assert.equal(await readerLabel.locator('input[name=name]').inputValue(), 'reader-label'); await permission(project, 'EDIT', true); await permission(project, 'MANAGE', true);
    await readerLabel.getByRole('button', { name: 'Create label', exact: true }).click(); await readerLabel.waitFor({ state: 'hidden' }); await reader.getByText('reader-label', { exact: true }).waitFor();
    await reader.goto(`${base}${href}?tab=repositories`); const privateRow = reader.locator(`[data-workops-project-repository="${readerAssociation}"]`); await privateRow.getByText('Repository unavailable', { exact: true }).waitFor();
    await privateRow.getByRole('button', { name: 'Create deploy config for Repository unavailable', exact: true }).click(); const readerSetup = dialog('Create deploy config', reader); await readerSetup.waitFor(); await readerSetup.locator('input[name=authorName]').fill('Reader Human'); await readerSetup.locator('input[name=authorEmail]').fill('reader.human@example.test');
    await permission(project, 'MANAGE', false); await permission(project, 'EDIT', false); rejection(true);
    await readerSetup.getByRole('button', { name: 'Create and commit deploy config', exact: true }).click(); await readerSetup.getByRole('alert').filter({ hasText: /.+/ }).waitFor(); rejection(false);
    assert.equal(await readerSetup.locator('input[name=authorEmail]').inputValue(), 'reader.human@example.test'); await permission(project, 'EDIT', true); await permission(project, 'MANAGE', true);
    await readerSetup.getByRole('button', { name: 'Create and commit deploy config', exact: true }).click(); await readerSetup.waitFor({ state: 'hidden' });
    await dialog('Committed deploy config', reader).waitFor(); commit = await head(readerRepository);
    assert.equal(commit.authorName, 'Reader Human'); assert.equal(commit.committerName, 'Reader Human'); assert.equal(commit.authorEmail, 'reader.human@example.test'); assert.ok((await blob(readerRepository)).content);
  } finally { await readerContext.close(); for (const action of ['MANAGE', 'EDIT', 'VIEW']) await permission(project, action, false); }
}
