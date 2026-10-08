const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { inventory, sha256, targets, validateReview } = require('../desktop/release.cjs');
const root = path.join(__dirname, '..');
const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
const write = (file, value) => { fs.writeFileSync(`${file}.partial`, JSON.stringify(value, null, 2) + '\n'); fs.renameSync(`${file}.partial`, file); };
function collect(service, output, target) {
  if (!targets.has(target)) throw new Error('Unsupported inventory target');
  if (git(['status', '--porcelain', '--untracked-files=normal'])) throw new Error('Source archive requires clean committed tree');
  const commit = git(['rev-parse', 'HEAD']);
  fs.mkdirSync(output, { recursive: true });
  const source = `pathlab-forge-${commit}.tar`;
  execFileSync('git', ['archive', '--format=tar', `--output=${path.join(output, source)}`, commit], { cwd: root });
  const files = inventory(service);
  const dependencyFile = path.join(output, 'java-dependencies.json');
  if (!fs.existsSync(dependencyFile)) throw new Error('Run distributionDependencies before collecting inputs');
  const lockfiles = ['frontend/pnpm-lock.yaml', 'desktop/pnpm-lock.yaml', 'reader-runtime.lock.properties', 'gradle/wrapper/gradle-wrapper.properties'];
  const dependencyInputs = lockfiles.map(file => ({ file, sha256: sha256(path.join(root, file)) }));
  const npm = [];
  for (const project of ['frontend', 'desktop']) {
    const store = path.join(root, project, 'node_modules');
    if (!fs.existsSync(store)) throw new Error(`Install frozen ${project} dependencies first`);
    const moduleDirectories = [store];
    for (const modules of moduleDirectories) {
      const names = fs.readdirSync(modules).flatMap(name => name.startsWith('@')
        ? fs.readdirSync(path.join(modules, name)).map(child => `${name}/${child}`) : [name]);
      for (const name of names) {
        const packageRoot = path.join(modules, name);
        if (fs.lstatSync(packageRoot).isSymbolicLink() || !fs.existsSync(path.join(packageRoot, 'package.json'))) continue;
        if (fs.existsSync(path.join(packageRoot, 'node_modules'))) moduleDirectories.push(path.join(packageRoot, 'node_modules'));
        const metadata = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'));
        const legal = fs.readdirSync(packageRoot).filter(file => /^(license|notice|copying|copyright)/i.test(file)
          && fs.statSync(path.join(packageRoot, file)).isFile());
        const notices = legal.map(file => {
          const source = path.join(packageRoot, file);
          const digest = sha256(source);
          const destination = path.join(output, 'npm-notices', digest);
          fs.mkdirSync(path.dirname(destination), { recursive: true });
          fs.copyFileSync(source, destination);
          return { file: `npm-notices/${digest}`, sha256: digest };
        });
        npm.push({ project, name: metadata.name, version: metadata.version, declaredLicense: metadata.license || 'UNKNOWN',
          packageJsonSha256: sha256(path.join(packageRoot, 'package.json')), decision: 'PENDING_REVIEW', notices });
      }
    }
  }
  if (npm.length === 0) throw new Error('No installed dependency metadata discovered');
  write(path.join(output, 'npm-dependencies.json'), npm);
  const receipt = { schema: 'pathlab.forge.inventory/1', distribution: 'NON_REDISTRIBUTABLE_PENDING_REVIEW',
    commit, version: require('../desktop/package.json').version, target,
    source: { file: source, sha256: sha256(path.join(output, source)) }, files, dependencyInputs,
    javaDependenciesSha256: sha256(dependencyFile), npmDependenciesSha256: sha256(path.join(output, 'npm-dependencies.json')) };
  write(path.join(output, 'inventory.json'), receipt);
  const notices = files.filter(file => /(^|\/)(legal|licenses?)(\/|$)|(^|\/)(notice|copying|copyright)/i.test(file.path));
  write(path.join(output, 'notice-inventory.json'), { schema: 'pathlab.forge.notice-inventory/1', commit, files: notices });
  // Discovery only: exact bytes do not confer redistribution rights.
  return receipt;
}
function validateCatalog(directory, catalog) {
  const receipt = JSON.parse(fs.readFileSync(path.join(directory, 'inventory.json'), 'utf8'));
  validateReview(JSON.parse(fs.readFileSync(path.join(directory, 'review.json'), 'utf8')), receipt, directory);
  if (catalog.schema !== 'pathlab.forge.release/1' || catalog.commit !== receipt.commit
      || catalog.version !== receipt.version || catalog.target !== receipt.target
      || catalog.channel !== (/-rc\.[1-9][0-9]*$/.test(receipt.version) ? 'candidate' : 'stable')
      || catalog.inventorySha256 !== sha256(path.join(directory, 'inventory.json'))
      || catalog.sourceSha256 !== receipt.source.sha256) throw new Error('Catalog identity mismatch');
  for (const name of ['artifact', 'nativeAcceptance', 'signatureVerification']) {
    const item = catalog[name];
    if (!item || typeof item.file !== 'string' || !/^[a-f0-9]{64}$/.test(item.sha256)) throw new Error(`Missing ${name}`);
    const file = path.resolve(directory, item.file);
    if (!file.startsWith(path.resolve(directory) + path.sep) || sha256(file) !== item.sha256) throw new Error(`Changed ${name}`);
  }
  const expected = receipt.target === 'win32-x64' ? '.exe' : '.dmg';
  if (!catalog.artifact.file.endsWith(expected) || catalog.artifact.bytes !== fs.statSync(path.resolve(directory, catalog.artifact.file)).size) throw new Error('Artifact type/size mismatch');
  const acceptance = JSON.parse(fs.readFileSync(path.resolve(directory, catalog.nativeAcceptance.file), 'utf8'));
  const signature = JSON.parse(fs.readFileSync(path.resolve(directory, catalog.signatureVerification.file), 'utf8'));
  for (const record of [acceptance, signature]) {
    if (record.commit !== receipt.commit || record.target !== receipt.target || record.artifactSha256 !== catalog.artifact.sha256
        || record.version !== receipt.version || record.result !== 'PASS') throw new Error('Final artifact evidence mismatch');
  }
  const required = receipt.target === 'win32-x64' ? ['Windows 10 22H2', 'Windows 11'] : ['macOS 14'];
  if (!required.every(os => acceptance.platforms?.includes(os)) || acceptance.dataPreserved !== true
      || acceptance.upgradeRollback !== true || acceptance.accessibility !== true || acceptance.journeys !== true) throw new Error('Native acceptance incomplete');
  if (signature.timestampVerified !== true || signature.nestedVerified !== true
      || (receipt.target.startsWith('darwin') && (signature.notarized !== true || signature.stapled !== true))) throw new Error('Signature evidence incomplete');
  return catalog;
}
if (require.main === module) {
  try {
    const [command, directory, input, target] = process.argv.slice(2);
    if (command === 'inventory') collect(path.resolve(input), path.resolve(directory), target);
    else if (command === 'validate') validateCatalog(path.resolve(directory), JSON.parse(fs.readFileSync(input, 'utf8')));
    else throw new Error('Usage: node scripts/distribution.cjs inventory <output> <service> <win32-x64|darwin-x64|darwin-arm64> | validate <inputs> <catalog.json>');
    console.log(command === 'inventory' ? 'Inventory generated: NON_REDISTRIBUTABLE_PENDING_REVIEW' : 'Exact catalog inputs verified; publication remains separately authorized');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { collect, validateCatalog };
