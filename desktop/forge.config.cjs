const fs = require('node:fs');
const path = require('node:path');
module.exports = {
  packagerConfig: {
    asar: true,
    executableName: 'PathLabForge',
    appBundleId: 'org.pathlab.forge',
    extraResource: [path.join(__dirname, 'resources/service')],
    ignore: [/^\/resources(?:\/|$)/, /^\/test(?:\/|$)/, /^\/out(?:\/|$)/],
  },
  makers: [
    { name: '@electron-forge/maker-squirrel', config: { name: 'PathLabForge' } },
    { name: '@electron-forge/maker-dmg', platforms: ['darwin'], config: { format: 'ULFO' } },
  ],
  hooks: {
    prePackage: async (_config, platform, arch) => {
      const root = path.join(__dirname, 'resources/service');
      const manifest = JSON.parse(fs.readFileSync(path.join(root, 'runtime-manifest.json'), 'utf8'));
      if (manifest.platform !== platform || manifest.arch !== arch) {
        throw new Error('Staged Java runtime does not match Electron target');
      }
      if (!fs.existsSync(path.join(root, 'runtime/bin', platform === 'win32' ? 'java.exe' : 'java'))
          || !fs.readdirSync(path.join(root, 'lib')).some(name => name.endsWith('.jar'))) {
        throw new Error('Stage the matching Java service before packaging');
      }
    },
  },
};
