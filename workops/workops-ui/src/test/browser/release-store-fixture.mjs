import { generateKeyPairSync } from 'node:crypto';

export async function releaseStoreFixture(gql, fixtureProject) {
  const parent = (await gql('query($id:UUID!){workOps{projects{project(id:$id){ownerProfileId program{portfolio{id}}}}}}', { id: fixtureProject })).workOps.projects.project;
  const program = (await gql('mutation($input:WorkOpsProgramInput!){workOps{programs{create(input:$input){id}}}}', { input: { key: 'RSTORE', name: 'Release store health acceptance', portfolioId: parent.program.portfolio.id, ownerProfileId: parent.ownerProfileId } })).workOps.programs.create.id;
  const project = (await gql('mutation($input:WorkOpsProjectInput!){workOps{projects{create(input:$input){id}}}}', { input: { key: 'RSTOREAPP', name: 'Store application', programId: program, ownerProfileId: parent.ownerProfileId } })).workOps.projects.create.id;
  const repository = (await gql('mutation($input:CreateGitRepositoryInput!){git{createRepository(input:$input){id}}}', { input: { ownerId: parent.ownerProfileId, slug: 'atlas-release-store', name: 'Store health fixture', visibility: 'PRIVATE', defaultBranch: 'main', initializeWithReadme: true } })).git.createRepository.id;
  await gql('mutation($project:UUID!,$repository:UUID!){workOps{projects{addProjectRepository(projectId:$project,repositoryId:$repository){id}}}}', { project, repository });
  const type = (await gql('mutation{workOps{multiRepo{createEnvironmentType(name:"Store health fixture stage"){id}}}}')).workOps.multiRepo.createEnvironmentType.id;
  for (const [key, name, targetType] of [['play-production', 'Play production', 'PLAY_TRACK'], ['app-production', 'App Store production', 'APP_STORE']]) {
    await gql('mutation($input:CreateWorkOpsEnvironmentInput!){workOps{multiRepo{createEnvironment(input:$input){id}}}}', { input: { programId: program, key, name, typeId: type, targetType } });
  }
  const config = `environments:
  play-production:
    target: google_play
    artifact:
      namespace: fixtures
      coordinate: 'mobile:1.0.0'
    config:
      packageName: io.atlas.play
      track: production
      serviceAccountSecret: atlas-store-play
  app-production:
    target: app_store
    config:
      bundleId: io.atlas.ios
      ascKeySecret: atlas-store-asc
`;
  await gql('mutation($input:CommitFileInput!){git{commitFile(input:$input){commitSha}}}', { input: { repositoryId: repository, branch: 'main', path: '.bosca/deploy.yaml', content: config, message: 'Configure store health fixture', authorName: 'Atlas Release Tester', authorEmail: 'release-tester@example.test' } });
  const privateKey = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey.export({ type: 'pkcs8', format: 'pem' });
  for (const [name, value] of [['atlas-store-play', 'atlas-store-fixture-play'], ['atlas-store-asc', JSON.stringify({ issuerId: 'atlas-store-fixture', keyId: 'fixture-key', privateKey })]]) {
    await gql('mutation($name:String!,$value:String!){pipelines{setSecret(name:$name,value:$value){name}}}', { name, value });
  }
  const versions = [];
  for (const name of ['1.0.0', '2.0.0']) {
    versions.push((await gql('mutation($input:CreateWorkOpsVersionInput!){workOps{versions{create(input:$input){id}}}}', { input: { projectId: project, name } })).workOps.versions.create.id);
    await gql('mutation($id:UUID!,$tag:String!){git{createTag(repositoryId:$id,tag:$tag,targetRef:"main",taggerName:"Atlas Release Tester",taggerEmail:"release-tester@example.test"){name}}}', { id: repository, tag: name });
  }
  const release = (await gql('mutation($input:CreateWorkOpsReleaseInput!){workOps{crossProject{createRelease(input:$input){id}}}}', { input: { programId: program, name: 'Store health acceptance' } })).workOps.crossProject.createRelease.id;
  let bundled;
  const bundle = async version => {
    if (bundled) await gql('mutation($release:UUID!,$version:UUID!){workOps{crossProject{unbundleVersion(releaseId:$release,versionId:$version)}}}', { release, version: bundled });
    bundled = version;
    if (version) await gql('mutation($release:UUID!,$project:UUID!,$version:UUID!){workOps{crossProject{bundleVersion(releaseId:$release,projectId:$project,versionId:$version)}}}', { release, project, version });
  };
  await bundle(versions[0]);
  const permission = add => gql(`mutation($id:UUID!,$group:UUID!){workOps{programs{${add ? 'add' : 'remove'}Permission(id:$id,groupId:$group,action:VIEW)}}}`, { id: program, group: process.env.ATLAS_WORKOPS_TEST_READER_GROUP });
  return { program, project, repository, release, versions, bundle, permission };
}
