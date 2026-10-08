const fs = require('node:fs');
const path = require('node:path');
const release = require('./release.cjs');
const serviceRoot = path.join(__dirname, 'resources/service');
const manifestFile = path.join(serviceRoot, 'runtime-manifest.json');
const production = fs.existsSync(manifestFile) && JSON.parse(fs.readFileSync(manifestFile, 'utf8')).distribution === 'PRODUCTION';
const signatures = production ? release.signing(process.platform) : {};
module.exports = {
  packagerConfig: {
    asar: true,
    executableName: 'PathLabForge',
    appBundleId: 'org.pathlab.forge',
    ...signatures,
    appVersion: require('./package.json').version,
    extendInfo: { LSMinimumSystemVersion: '14.0' },
    extraResource: [path.join(__dirname, 'resources/service')],
    ignore: [/^\/resources(?:\/|$)/, /^\/test(?:\/|$)/, /^\/out(?:\/|$)/],
  },
  makers: [
    { name: '@electron-forge/maker-squirrel', platforms: ['win32'], config: { name: 'PathLabForge',
      noDelta: true, ...signatures } },
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
      return results;
    },
  },
};
