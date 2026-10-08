const fs = require('node:fs');
const path = require('node:path');
const release = require('./release.cjs');
const serviceRoot = path.join(__dirname, 'resources/service');
const manifestFile = path.join(serviceRoot, 'runtime-manifest.json');
const production = fs.existsSync(manifestFile) && JSON.parse(fs.readFileSync(manifestFile, 'utf8')).distribution === 'PRODUCTION';
const signatures = production ? release.signing(process.platform) : {};
const policies = new Map();
if (signatures.windowsSign) {
  signatures.windowsSign.hookModulePath = path.join(__dirname, 'windows-sign-hook.cjs');
}
module.exports = {
  packagerConfig: {
    asar: true,
    executableName: 'PathLabForge',
    appBundleId: 'org.pathlab.forge',
    ...signatures,
    appVersion: require('./package.json').version,
    extendInfo: { LSMinimumSystemVersion: '14.0' },
    extraResource: [path.join(__dirname, 'resources/service')],
    afterExtract: [async ({ buildPath, platform, arch }) => {
      const { flipFuses, getCurrentFuseWire, FuseState, FuseVersion, FuseV1Options } = await import('@electron/fuses');
      const binary = platform === 'darwin' ? path.join(buildPath, 'Electron.app') : path.join(buildPath, 'electron.exe');
      const values = {
        [FuseV1Options.RunAsNode]: false,
        [FuseV1Options.EnableCookieEncryption]: false,
        [FuseV1Options.EnableNodeOptionsEnvironmentVariable]: false,
        [FuseV1Options.EnableNodeCliInspectArguments]: false,
        [FuseV1Options.EnableEmbeddedAsarIntegrityValidation]: true,
        [FuseV1Options.OnlyLoadAppFromAsar]: true,
        [FuseV1Options.LoadBrowserProcessSpecificV8Snapshot]: false,
        [FuseV1Options.GrantFileProtocolExtraPrivileges]: false,
        [FuseV1Options.WasmTrapHandlers]: true,
      };
      await flipFuses(binary, { version: FuseVersion.V1, strictlyRequireAllFuses: true,
        resetAdHocDarwinSignature: platform === 'darwin' && arch === 'arm64', ...values });
      const actual = await getCurrentFuseWire(binary);
      for (const [key, value] of Object.entries(values)) if (actual[key] !== (value ? FuseState.ENABLE : FuseState.DISABLE)) throw new Error('Electron fuse verification failed');
    }],
    beforeAsar: [async ({ buildPath, platform, arch }) => {
      if (release.preflight(serviceRoot, platform, arch) !== production) throw new Error('Distribution channel changed');
      const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
      const { execFileSync } = require('node:child_process');
      const repository = path.join(__dirname, '..');
      const policy = { schema: 'pathlab.forge.activation-policy/1',
        version: require('./package.json').version, platform, arch, distribution: manifest.distribution,
        commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8' }).trim(),
        sourceDirty: !!execFileSync('git', ['status', '--porcelain', '--untracked-files=normal'], { cwd: repository, encoding: 'utf8' }).trim(),
        manifest, files: release.inventory(serviceRoot) };
      policies.set(`${platform}-${arch}`, policy);
      fs.writeFileSync(path.join(buildPath, 'release-policy.json'), JSON.stringify(policy) + '\n');
    }],
    afterCopyExtraResources: [async ({ buildPath, platform, arch }) => {
      const copied = platform === 'darwin'
        ? path.join(buildPath, 'PathLab Forge.app/Contents/Resources/service')
        : path.join(buildPath, 'resources/service');
      const policy = policies.get(`${platform}-${arch}`);
      if (!policy) throw new Error('Missing pinned release policy');
      release.exactInventory(copied, policy.files);
    }],
    afterComplete: [async ({ buildPath, platform, arch }) => {
      const appRoot = platform === 'darwin' ? path.join(buildPath, 'PathLab Forge.app') : buildPath;
      const resources = platform === 'darwin' ? path.join(appRoot, 'Contents/Resources') : path.join(appRoot, 'resources');
      const { extractFile } = await import('@electron/asar');
      const policy = JSON.parse(extractFile(path.join(resources, 'app.asar'), 'release-policy.json').toString('utf8'));
      require('./src/activation.cjs').activate(path.join(resources, 'service'), policy, platform, arch, require('./package.json').version);
      const { getCurrentFuseWire, FuseV1Options, FuseState } = await import('@electron/fuses');
      const actual = await getCurrentFuseWire(platform === 'darwin' ? appRoot : path.join(appRoot, 'PathLabForge.exe'));
      for (const key of [FuseV1Options.RunAsNode, FuseV1Options.EnableNodeOptionsEnvironmentVariable, FuseV1Options.EnableNodeCliInspectArguments]) {
        if (actual[key] !== FuseState.DISABLE) throw new Error('Unsafe final Electron fuse');
      }
      for (const key of [FuseV1Options.EnableEmbeddedAsarIntegrityValidation, FuseV1Options.OnlyLoadAppFromAsar]) {
        if (actual[key] !== FuseState.ENABLE) throw new Error('Missing final Electron integrity fuse');
      }
      // Read-only after signing: a changed service invalidates the pinned policy.
      const destination = path.join(__dirname, '../build/distribution-inputs');
      fs.mkdirSync(destination, { recursive: true });
      fs.writeFileSync(path.join(destination, `final-app-${platform}-${arch}.json`), JSON.stringify({
        schema: 'pathlab.forge.final-app-inventory/1', distribution: policy.distribution,
        platform, arch, version: policy.version, commit: policy.commit, sourceDirty: policy.sourceDirty, files: release.inventory(appRoot) }) + '\n');
    }],
    ignore: [/^\/resources(?:\/|$)/, /^\/test(?:\/|$)/, /^\/out(?:\/|$)/, /^\/release-policy\.json$/],
  },
  makers: [
    { name: '@electron-forge/maker-squirrel', platforms: ['win32'], config: { name: 'PathLabForge',
      noDelta: true, additionalFiles: [{ src: 'LICENSES.chromium.html', target: 'lib\\net45\\LICENSES.chromium.html' }, { src: 'version', target: 'lib\\net45\\version' }], ...signatures } },
    { name: '@electron-forge/maker-dmg', platforms: ['darwin'], config: { format: 'ULFO' } },
  ],
  hooks: {
    prePackage: async (_config, platform, arch) => {
      if (release.preflight(serviceRoot, platform, arch) !== production) throw new Error('Staged distribution channel changed during packaging');
    },
    postMake: async (_config, results) => {
      if (production && process.platform === 'darwin') {
        const { execFileSync } = require('node:child_process');
        for (const result of results) for (const artifact of result.artifacts.filter(file => file.endsWith('.dmg'))) {
          const submission = execFileSync('xcrun', ['notarytool', 'submit', artifact, '--keychain-profile', process.env.PATHLAB_FORGE_NOTARY_PROFILE, '--wait', '--output-format', 'json'],
            { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
          if (JSON.parse(submission).status !== 'Accepted') throw new Error('DMG notarization was not accepted');
          execFileSync('xcrun', ['stapler', 'staple', artifact], { stdio: ['ignore', 'pipe', 'pipe'] });
          execFileSync('xcrun', ['stapler', 'validate', artifact], { stdio: ['ignore', 'pipe', 'pipe'] });
        }
      }
      const { withPayload, comparePayload } = require('./installer-payload.cjs');
      for (const result of results) for (const artifact of result.artifacts.filter(file => /\.(exe|dmg)$/.test(file))) {
        const file = path.join(__dirname, `../build/distribution-inputs/final-app-${result.platform}-${result.arch}.json`);
        const expected = JSON.parse(fs.readFileSync(file, 'utf8'));
        await withPayload(artifact, async payload => {
          const files = comparePayload(payload, expected.files, result.platform === 'win32');
          fs.writeFileSync(file, JSON.stringify({ ...expected, files, payloadBound: true, artifactSha256: release.sha256(artifact) }) + '\n');
        }, result.platform);
      }
      return results;
    },
  },
};
