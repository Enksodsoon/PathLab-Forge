const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('forgeDesktop', Object.freeze({
  selectSources: () => ipcRenderer.invoke('forge:select-sources'),
  selectFeatureFiles: () => ipcRenderer.invoke('forge:select-feature-files'),
  selectDirectory: () => ipcRenderer.invoke('forge:select-directory'),
  selectExportDestination: name => ipcRenderer.invoke('forge:export-destination', name),
  revealPath: path => ipcRenderer.invoke('forge:reveal', path),
  openExternal: url => ipcRenderer.invoke('forge:external', url),
  onCommand: callback => {
    if (typeof callback !== 'function') throw new TypeError('Expected callback');
    const listener = (_event, command) => callback(command);
    ipcRenderer.on('forge:command', listener);
    return () => ipcRenderer.removeListener('forge:command', listener);
  },
}));
