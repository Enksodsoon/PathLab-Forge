const path = require('node:path');
function localUrl(value, origin) {
  try {
    const url = new URL(value);
    return url.origin === origin && !url.username && !url.password;
  } catch { return false; }
}
function readiness(line) {
  if (!line.startsWith('PATHLAB_FORGE_READY ')) return null;
  const record = JSON.parse(line.slice('PATHLAB_FORGE_READY '.length));
  const url = new URL(record.origin);
  if (record.protocol !== 1 || url.protocol !== 'http:' || url.hostname !== '127.0.0.1'
      || !url.port || url.username || url.password || url.pathname !== '/' || url.search || url.hash
      || !localUrl(record.launchUrl, url.origin)
      || typeof record.desktopSecret !== 'string' || !/^[A-Za-z0-9_-]{32,128}$/.test(record.desktopSecret)) throw new Error('Invalid private readiness record');
  return { origin: url.origin, launchUrl: record.launchUrl, desktopSecret: record.desktopSecret };
}
function externalUrl(value, origins) {
  if (typeof value !== 'string' || value.length > 4096) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && origins.includes(url.origin);
  } catch { return false; }
}
function startupFailure(line) {
  if (!line.startsWith('PATHLAB_FORGE_FAILED ')) return null;
  const record = JSON.parse(line.slice('PATHLAB_FORGE_FAILED '.length));
  const messages = {
    DATA_LOCKED: 'Another Forge process owns this library. Close it, then reopen Forge. Do not delete the library lock or data.',
    DATA_DENIED: 'Forge cannot access its application-data folder. Restore read/write permission or free the drive, then reopen Forge.',
    DATA_UPGRADE_BACKUP_FAILED: 'Forge could not create the required upgrade backup. Free disk space and restore write permission, then reopen Forge. Preserve the library and existing backups.',
    DATA_DOWNGRADE_BLOCKED: 'This library requires a newer Forge version. Reinstall the matching newer version. A downgrade requires a separately restored compatible backup; preserve the current library.',
    DATA_VERSION_REQUIRED: 'Forge cannot verify this library\'s data version. Preserve the library and backups, and use a release compatible with its recorded data version before upgrading.',
    DATA_UPGRADE_RECOVERY_REQUIRED: 'An interrupted library upgrade requires recovery. Close all Forge processes and recover a complete compatible backup before reopening. Preserve the current library and backups.',
    SERVICE_UNAVAILABLE: 'The local service could not initialize. Verify the installed runtime and available disk space. Reinstall the matching Forge version while preserving application data.'
  };
  if (record.protocol !== 1 || !Object.hasOwn(messages, record.code)) throw new Error('Invalid private startup failure');
  return messages[record.code];
}
function allowedPath(value, selected) {
  if (typeof value !== 'string' || value.length > 32768 || value.includes('\0') || !path.isAbsolute(value)) return false;
  return selected.has(path.resolve(value));
}
function windowState(value) {
  const state = { width: 1280, height: 840 };
  if (value && Number.isInteger(value.width) && value.width >= 640 && value.width <= 8192
      && Number.isInteger(value.height) && value.height >= 480 && value.height <= 8192) {
    state.width = value.width; state.height = value.height;
  }
  return state;
}
function serviceEnvironment(environment) {
  return Object.fromEntries(Object.entries(environment).filter(([key]) =>
    !/^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|CLASSPATH|JAVA_HOME|LD_PRELOAD|DYLD_INSERT_LIBRARIES|DYLD_LIBRARY_PATH)$/i.test(key)
    && !/^PATHLAB_FORGE_/i.test(key)));
}
module.exports = { localUrl, readiness, startupFailure, externalUrl, allowedPath, windowState, serviceEnvironment };
