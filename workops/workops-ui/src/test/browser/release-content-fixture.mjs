import assert from 'node:assert/strict';
import { until } from './release-ci-fixture.mjs';

export async function releaseContentFixture(gql, fixtureProject, configureNotes) {
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { key: 'RCN', name: 'Release content acceptance', portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId } })).workOps.programs.create.id;
  const privateProgram = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { key: 'RCNPRIVATE', name: 'Private release project destination', portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId } })).workOps.programs.create.id;
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { key: 'RCNAPP', name: 'Release content application', programId: program, ownerProfileId: parent.ownerProfileId } })).workOps.projects.create.id;
  const repository = (await gql('mutation($input:CreateGitRepositoryInput!){git{createRepository(input:$input){id}}}', { input: { ownerId: parent.ownerProfileId, slug: 'atlas-release-content', name: 'Release content fixture', visibility: 'PRIVATE', defaultBranch: 'main', initializeWithReadme: true } })).git.createRepository.id;
  await gql('mutation($project:UUID!,$repository:UUID!){workOps{projects{addProjectRepository(projectId:$project,repositoryId:$repository){id}}}}', { project, repository });
  const yaml = `name: Release content\non:\n  release: {}\njobs:\n  build:\n    runner: atlas-release-content\n    steps:\n      - run: echo fixture\n    artifacts:\n      - type: docker\n        namespace: images\n        coordinate: 'content:1.0.0'\n        environments: [testing, production]\n`;
  await gql('mutation($input:CommitFileInput!){git{commitFile(input:$input){commitSha}}}', { input: { repositoryId: repository, branch: 'main', path: '.bosca/pipelines/content.yaml', content: yaml, message: 'Improve release screen', authorName: 'Atlas Release Tester', authorEmail: 'release-tester@example.test' } });
  await until('Artifact declaration synchronized', () => gql('query($id:UUID!){git{pipelines(repositoryId:$id){id}}}', { id: repository }).then(data => data.git.pipelines), rows => rows.length === 1);
  const version = (await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id}}}}', { input: { projectId: project, name: '1.0.0' } })).workOps.versions.create.id;
  const release = (await gql('mutation($input:CreateWorkOpsReleaseInput!){workOps{crossProject{createRelease(input:$input){id}}}}', { input: { programId: program, name: 'Release content acceptance' } })).workOps.crossProject.createRelease.id;
  const bundle = add => gql(`mutation($release:UUID!,${add ? '$project:UUID!,' : ''}$version:UUID!){workOps{crossProject{${add ? 'bundleVersion(releaseId:$release,projectId:$project,versionId:$version)' : 'unbundleVersion(releaseId:$release,versionId:$version)'}}}}`, { release, project, version });
  await bundle(true);
  await gql('mutation($id:UUID!){git{createTag(repositoryId:$id,tag:"1.0.0",targetRef:"main",taggerName:"Atlas Release Tester",taggerEmail:"release-tester@example.test"){name}}}', { id: repository });
  const localization = (await configureNotes({ action: 'create', project })).id;
  const group = process.env.ATLAS_WORKOPS_TEST_READER_GROUP;
  const permission = (action, add) => gql(`mutation($id:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:${action})}}}`, { id: program, group });
  const projectPermission = add => gql(`mutation($id:UUID!,$group:UUID!){workOps{projects{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:VIEW)}}}`, { id: project, group });
  const moveProject = async destination => {
    const current = (await gql('query($id:UUID!){workOps{projects{project(id:$id){version}}}}', { id: project })).workOps.projects.project;
    await gql('mutation($id:UUID!,$program:UUID!,$version:Long!){workOps{projects{move(id:$id,programId:$program,expectedVersion:$version){id}}}}', { id: project, program: destination, version: current.version });
  };
  const notes = () => gql('query($id:UUID!){workOps{multiRepo{versionReleaseNotes(releaseId:$id){versionId manuallyEdited variants}}}}', { id: release }).then(data => data.workOps.multiRepo.versionReleaseNotes);
  const publication = (await gql('mutation($input:RegisterWorkOpsArtifactInput!){workOps{multiRepo{registerArtifact(input:$input){id version status}}}}', { input: { versionId: version, projectId: project, artifactType: 'DOCKER', coordinates: 'content:1.0.0' } })).workOps.multiRepo.registerArtifact;
  assert.equal(publication.status, 'PENDING');
  const publish = () => gql('mutation($id:UUID!,$version:Long!){workOps{multiRepo{markArtifactPublished(id:$id,expectedVersion:$version){id status}}}}', { id: publication.id, version: publication.version });
  const breakNotesRead = async () => {
    await configureNotes({ action: 'breakRead', localization, version });
  };
  const restoreNotes = variants => gql('mutation($release:UUID!,$version:UUID!,$variants:JSON!){workOps{multiRepo{editVersionReleaseNotes(releaseId:$release,versionId:$version,variants:$variants){id}}}}', { release, version, variants });
  const hideProject = async () => { await projectPermission(false); await moveProject(privateProgram); };
  const restore = async () => { await bundle(true); await moveProject(program); await projectPermission(false); for (const action of ['MANAGE', 'VIEW']) await permission(action, false); };
  return { program, project, repository, version, release, localization, publication, permission, projectPermission, hideProject, notes, bundle, publish, breakNotesRead, restoreNotes, restore };
}
