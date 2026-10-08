export {}
declare global {
  interface Window {
    forgeDesktop?: {
      selectSources(): Promise<string[]>
      selectDirectory(): Promise<string | null>
      selectExportDestination(name: string): Promise<string | null>
      revealPath(path: string): Promise<void>
      openExternal(url: string): Promise<void>
      onCommand(callback: (command: string) => void): () => void
    }
  }
}
