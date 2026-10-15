// Module hook survives Squirrel's JSON/SEA boundary; a captured function does not.
const { pathToFileURL } = require('node:url');
const { signing } = require('./release.cjs');
const { verifyWindows } = require('./native-signing.cjs');
module.exports = async file => {
  try { verifyWindows([file]); return; }
  catch { /* Sign new files once; keep reviewed signed payload bytes unchanged. */ }
  const { sign } = await import(pathToFileURL(require.resolve('@electron/windows-sign')).href);
  await sign({ ...signing('win32').windowsSign, files: [file] });
};
