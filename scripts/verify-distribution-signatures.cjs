// Native final-artifact verification. No credentials are accepted by this tool.
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { sha256, validateReview } = require('../desktop/release.cjs');
const { verifyWindows, verifyMac, nativeFiles, run } = require('../desktop/native-signing.cjs');
const { activate } = require('../desktop/src/activation.cjs');
const { withPayload, comparePayload } = require('../desktop/installer-payload.cjs');
async function verify(input, artifact, _untrustedAppDirectory, output) {
  const receipt = JSON.parse(fs.readFileSync(input, 'utf8'));
  if (receipt.target !== `${process.platform}-${process.arch}`) throw new Error('Native signature target mismatch');
  validateReview(JSON.parse(fs.readFileSync(path.join(path.dirname(input), 'review.json'), 'utf8')), receipt, path.dirname(input));
  const appInventoryFile = path.join(path.dirname(input), `final-app-${process.platform}-${process.arch}.json`);
  const finalInventory = JSON.parse(fs.readFileSync(appInventoryFile, 'utf8'));
  if (finalInventory.commit !== receipt.commit || finalInventory.version !== receipt.version
      || finalInventory.platform !== process.platform || finalInventory.arch !== process.arch
      || finalInventory.sourceDirty !== false || finalInventory.payloadBound !== true
      || finalInventory.artifactSha256 !== sha256(artifact) || finalInventory.distribution !== 'PRODUCTION') throw new Error('Missing exact installer payload inventory');
  if (process.platform === 'win32') verifyWindows([path.resolve(artifact)]);
  else if (process.platform === 'darwin') run('xcrun', ['stapler', 'validate', path.resolve(artifact)]);
  else throw new Error('Unsupported signing host');
  await withPayload(artifact, async (app, bootstrap) => {
  comparePayload(app, finalInventory.files);
  const resources = process.platform === 'darwin' ? path.join(app, 'Contents/Resources') : path.join(app, 'resources');
  const { extractFile } = await import(pathToFileURL(require.resolve('@electron/asar', { paths: [path.join(__dirname, '../desktop')] })).href);
  const policy = JSON.parse(extractFile(path.join(resources, 'app.asar'), 'release-policy.json').toString('utf8'));
  if (policy.commit !== receipt.commit || policy.distribution !== 'PRODUCTION' || policy.sourceDirty !== false) throw new Error('Final packaged policy does not match reviewed source');
  activate(path.join(resources, 'service'), policy, process.platform, process.arch, receipt.version);
  const { getCurrentFuseWire, FuseV1Options, FuseState } = await import(pathToFileURL(require.resolve('@electron/fuses', { paths: [path.join(__dirname, '../desktop')] })).href);
  const fuses = await getCurrentFuseWire(process.platform === 'darwin' ? app : path.join(app, 'PathLabForge.exe'));
  for (const key of [FuseV1Options.RunAsNode, FuseV1Options.EnableNodeOptionsEnvironmentVariable, FuseV1Options.EnableNodeCliInspectArguments]) {
    if (fuses[key] !== FuseState.DISABLE) throw new Error('Unsafe final Electron fuse');
  }
  for (const key of [FuseV1Options.EnableEmbeddedAsarIntegrityValidation, FuseV1Options.OnlyLoadAppFromAsar]) {
    if (fuses[key] !== FuseState.ENABLE) throw new Error('Missing final Electron integrity fuse');
  }
  if (process.platform === 'win32') verifyWindows([path.join(bootstrap, 'Update.exe'), ...nativeFiles(app)]);
  else if (process.platform === 'darwin') {
    verifyMac([...nativeFiles(app), app]);
    run('codesign', ['--verify', '--deep', '--strict', '--verbose=2', app]);
    run('spctl', ['--assess', '--type', 'execute', '--verbose=2', app]);
    run('xcrun', ['stapler', 'validate', app]);
  } else throw new Error('Unsupported signing host');
  });
  const record = { schema: 'pathlab.forge.signature-verification/1', commit: receipt.commit, version: receipt.version,
    target: receipt.target, artifactSha256: sha256(artifact), result: 'PASS', appInventorySha256: sha256(appInventoryFile),
    fusesVerified: true, policyVerified: true, payloadVerified: true, timestampVerified: true, nestedVerified: true,
    notarized: process.platform === 'darwin', stapled: process.platform === 'darwin' };
  fs.writeFileSync(`${output}.partial`, JSON.stringify(record, null, 2) + '\n'); fs.renameSync(`${output}.partial`, output);
}
if (require.main === module) {
  const [input, artifact, app, output] = process.argv.slice(2);
  if (!input || !artifact || !app || !output) throw new Error('Pass inventory.json, final installer, packaged app directory, output receipt');
  verify(path.resolve(input), path.resolve(artifact), path.resolve(app), path.resolve(output))
    .catch(() => { console.error('Final artifact verification failed; no release receipt issued.'); process.exitCode = 1; });
}
module.exports = { verify };
