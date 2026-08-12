import {
  AnnotationToolbar,
  ViewerCanvasShell,
} from '@pathlab/viewer-ui'
import {
  ArrowsOut,
  ArrowsClockwise,
  CaretDown,
  CheckCircle,
  CloudArrowUp,
  Crosshair,
  Folder,
  FolderOpen,
  House,
  Key,
  List,
  MagnifyingGlassMinus,
  MagnifyingGlassPlus,
  Moon,
  Plus,
  SidebarSimple,
  SignOut,
  Sun,
  Trash,
  UploadSimple,
  Wrench,
} from '@phosphor-icons/react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent, ReactNode, Ref } from 'react'
import type OpenSeadragon from 'openseadragon'
import { renderSVG } from 'uqr'

import * as api from './api'
import type {
  AnnotationRecord,
  ArtifactRevision,
  Dataset,
  SeriesInfo,
  ViewerConnection,
  ViewerPairing,
} from './api'
import { estimateCropOutput, isFullSlideCrop, type CropBox } from './crop'
import { SlideViewer } from './SlideViewer'
import { DIRECT_PREVIEW_VERSION } from './viewerConfig'

const ACTIVE_STATUSES = new Set(['VERIFYING_SOURCE', 'INSPECTING', 'QUEUED', 'WAITING_RESOURCES', 'CONVERTING', 'OPTIMIZING_OME', 'VALIDATING', 'GENERATING_DZI', 'DZI_READY'])
const CONVERSION_STATUSES = new Set(['CONVERTING', 'OPTIMIZING_OME', 'VALIDATING', 'GENERATING_DZI', 'DZI_READY'])
const CANCELLABLE_STATUSES = new Set(['QUEUED', 'WAITING_RESOURCES', ...CONVERSION_STATUSES])
const QUEUEABLE_STATUSES = new Set(['READY', 'READY_TO_CONVERT', 'CONVERSION_READY', 'FAILED', 'CANCELLED'])
const NO_ANNOTATIONS: AnnotationRecord[] = []
const DEFAULT_LOCAL_FOLDER = 'Unfiled'

export function App() {
  const [datasets, setDatasets] = useState<Dataset[]>([])
  const [capabilities, setCapabilities] = useState<Awaited<ReturnType<typeof api.capabilities>>>()
  const [selectedId, setSelectedId] = useState('')
  const [seriesByDataset, setSeriesByDataset] = useState<Record<string, SeriesInfo[]>>({})
  const [artifactByDataset, setArtifactByDataset] = useState<Record<string, ArtifactRevision[]>>({})
  const [viewingRevisionByDataset, setViewingRevisionByDataset] = useState<Record<string, string>>({})
  const [navigatorOpen, setNavigatorOpen] = useState(
    () => typeof window === 'undefined' || window.innerWidth > 960,
  )
  const [inspectorOpen, setInspectorOpen] = useState(true)
  const [railExpanded, setRailExpanded] = useState(false)
  const [activeTool, setActiveTool] = useState('pan')
  const [cropDrafts, setCropDrafts] = useState<Record<string, CropBox>>(() => {
    try {
      return JSON.parse(window.localStorage.getItem('pathlab-forge-crop-drafts-v1') || '{}')
    } catch {
      return {}
    }
  })
  const [cropEditing, setCropEditing] = useState(false)
  const [viewer, setViewer] = useState<OpenSeadragon.Viewer | null>(null)
  const [notice, setNotice] = useState('Loading local workspace…')
  const [error, setError] = useState('')
  const [importOpen, setImportOpen] = useState(false)
  const [importing, setImporting] = useState(false)
  const [importPath, setImportPath] = useState('')
  const [removeTarget, setRemoveTarget] = useState<Dataset>()
  const [pairingOpen, setPairingOpen] = useState(false)
  const [viewerUrl, setViewerUrl] = useState('http://127.0.0.1:5173')
  const [pairing, setPairing] = useState<ViewerPairing>()
  const [connection, setConnection] = useState<ViewerConnection>()
  const [viewerUpload, setViewerUpload] = useState<api.ViewerUpload>()
  const [remoteLibrary, setRemoteLibrary] = useState<api.ViewerRemoteLibrary>({ items: [], folders: [], conflicts: [] })
  const [remoteSyncReady, setRemoteSyncReady] = useState(false)
  const [selectedRemoteId, setSelectedRemoteId] = useState('')
  const [annotationsByDataset, setAnnotationsByDataset] = useState<Record<string, AnnotationRecord[]>>({})
  const [featureOpen, setFeatureOpen] = useState(false)
  const [features, setFeatures] = useState<api.FeaturePack[]>([])
  const [featureLoading, setFeatureLoading] = useState(false)
  const [libraryMode, setLibraryMode] = useState<'local' | 'viewer'>('local')
  const [selectedDatasetIds, setSelectedDatasetIds] = useState<string[]>([])
  const [localFolders, setLocalFolders] = useState<string[]>(() => readStored('pathlab-forge-folders-v1', [DEFAULT_LOCAL_FOLDER]))
  const [folderByDataset, setFolderByDataset] = useState<Record<string, string>>(() => readStored('pathlab-forge-folder-map-v1', {}))
  const [batchRemoveIds, setBatchRemoveIds] = useState<string[]>([])
  const [theme, setTheme] = useState<'light' | 'dark'>(() => document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light')
  const navigatorButtonRef = useRef<HTMLButtonElement>(null)

  const selected = datasets.find((item) => item.id === selectedId) ?? datasets[0]
  const selectedRemote = remoteLibrary.items.find((item) => item.id === selectedRemoteId)
    ?? remoteLibrary.items[0]
  const cropDraft = selected ? cropDrafts[selected.id] : undefined
  const setCropDraft = useCallback((crop?: CropBox) => {
    if (!selected) return
    setCropDrafts((current) => {
      if (!crop) {
        const next = { ...current }
        delete next[selected.id]
        return next
      }
      return { ...current, [selected.id]: crop }
    })
  }, [selected?.id])
  const selectedSeries = selected ? seriesByDataset[selected.id] ?? [] : []
  const revisions = selected ? artifactByDataset[selected.id] ?? [] : []
  const currentRevision = revisions.find((revision) => revision.id === selected?.currentArtifactRevision)
  const viewingRevision = selected
    ? revisions.find((revision) => revision.id === viewingRevisionByDataset[selected.id])
    : undefined
  const selectedAnnotations = selected
    ? annotationsByDataset[selected.id] ?? NO_ANNOTATIONS
    : NO_ANNOTATIONS

  useEffect(() => {
    if (!selected || selected.width <= 0 || selected.height <= 0) {
      setCropEditing(false)
      return
    }
    setCropDrafts((current) => current[selected.id] ? current : ({
      ...current,
      [selected.id]: {
        x: selected.cropX,
        y: selected.cropY,
        width: selected.cropWidth || selected.width,
        height: selected.cropHeight || selected.height,
      },
    }))
    setCropEditing(false)
  }, [
    selected?.id,
    selected?.selectedSeries,
    selected?.cropX,
    selected?.cropY,
    selected?.cropWidth,
    selected?.cropHeight,
    selected?.width,
    selected?.height,
  ])

  useEffect(() => {
    window.localStorage.setItem('pathlab-forge-crop-drafts-v1', JSON.stringify(cropDrafts))
  }, [cropDrafts])

  useEffect(() => {
    window.localStorage.setItem('pathlab-forge-folders-v1', JSON.stringify(localFolders))
  }, [localFolders])

  useEffect(() => {
    window.localStorage.setItem('pathlab-forge-folder-map-v1', JSON.stringify(folderByDataset))
  }, [folderByDataset])

  const refresh = useCallback(async () => {
    try {
      const next = await api.datasets()
      setDatasets(next)
      setSelectedId((current) => current || next[0]?.id || '')
      setNotice(next.length ? 'Local workspace ready' : 'Choose a slide to begin')
    } catch (nextError) {
      setError(message(nextError))
    }
  }, [])

  useEffect(() => {
    void api.bootstrap()
      .then(([initialDatasets, initialCapabilities]) => {
        setDatasets(initialDatasets)
        setCapabilities(initialCapabilities)
        setSelectedId(initialDatasets[0]?.id || '')
        setNotice(initialDatasets.length ? 'Local workspace restored' : 'Choose a slide to begin')
        void api.getViewerConnection().then((next) => {
          setConnection(next)
          if (next.viewerUrl) setViewerUrl(next.viewerUrl)
        }).catch(() => undefined)
        void refresh()
      })
      .catch((nextError) => setError(message(nextError)))
  }, [])

  useEffect(() => {
    if (!datasets.some((item) => ACTIVE_STATUSES.has(item.status))) return
    const timer = window.setInterval(() => void refresh(), 1500)
    return () => window.clearInterval(timer)
  }, [datasets, refresh])

  useEffect(() => {
    if (!selected) return
    let cancelled = false
    void api.annotations(selected.id).then((items) => {
      if (!cancelled) {
        setAnnotationsByDataset((current) => ({ ...current, [selected.id]: items }))
      }
    }).catch(() => undefined)
    void api.artifacts(selected.id).then((result) => {
      if (!cancelled) {
        setArtifactByDataset((current) => ({ ...current, [selected.id]: result.revisions }))
        setViewingRevisionByDataset((current) => ({
          ...current,
          [selected.id]: result.revisions.some(
            (revision) => revision.id === current[selected.id] && revision.packageBytes > 0,
          )
            ? current[selected.id]
            : '',
        }))
      }
    }).catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [selected?.id, selected?.currentArtifactRevision, selected?.status])

  const finishImport = (next: { datasets: Dataset[] }) => {
    const imported = next.datasets.find((item) => !datasets.some((current) => current.id === item.id))
    const opensAutomatically = Boolean(imported && capabilities?.vsiConversion
      && ['READY', 'READER_REQUIRED', 'VERIFYING_SOURCE'].includes(imported.status))
    setDatasets(next.datasets.map((item) => opensAutomatically && item.id === imported?.id
      ? { ...item, status: 'INSPECTING', detail: 'Opening the slide reader and native pyramid' }
      : item))
    setSelectedId(imported?.id || next.datasets.at(-1)?.id || '')
    setImportOpen(false)
    setImportPath('')
    if (imported && opensAutomatically) {
      setNotice('Slide added — opening the native-resolution viewer…')
      void api.inspectDataset(imported.id).then((result) => {
        setSeriesByDataset((current) => ({ ...current, [imported.id]: result }))
        setNotice(`${result.length} image series ready`)
        return refresh()
      }).catch((nextError) => setError(message(nextError)))
    } else {
      setNotice(imported ? 'Dataset inventory created' : 'Slide is already in the local library')
    }
  }

  const handleNativeImport = async () => {
    setImporting(true)
    try {
      const next = await api.chooseDatasets()
      finishImport(next)
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setImporting(false)
    }
  }

  const handlePathImport = async () => {
    if (!importPath.trim()) return
    setImportOpen(false)
    setImporting(true)
    try {
      const next = await api.importDataset(importPath.trim())
      finishImport(next)
    } catch (nextError) {
      setError(message(nextError))
      setImportOpen(true)
    } finally {
      setImporting(false)
    }
  }

  const handleProjectImport = async (path?: string) => {
    setImporting(true)
    try {
      const next = await api.importProjectFolder(path)
      finishImport(next)
      setNotice(next.project
        ? `${next.project.imported} slides imported from project folder${next.project.failed ? ` · ${next.project.failed}` : ''}`
        : 'Project folder selection closed')
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setImporting(false)
    }
  }

  const removeDataset = async () => {
    if (!removeTarget) return
    try {
      const removedId = removeTarget.id
      await api.deleteDataset(removedId)
      const remaining = await api.datasets()
      setDatasets(remaining)
      setSelectedId((current) => current === removedId ? remaining[0]?.id || '' : current)
      setRemoveTarget(undefined)
      setNotice('Slide removed from the Forge library; original files and completed exports were preserved')
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const inspect = async () => {
    if (!selected) return
    try {
      if (selected.selectedSeries < 0) {
        setDatasets((current) => current.map((item) => item.id === selected.id
          ? { ...item, status: 'INSPECTING', detail: 'Opening the slide reader and native pyramid' }
          : item))
      }
      setNotice('Inspecting image series and native pyramid…')
      const result = await api.inspectDataset(selected.id)
      setSeriesByDataset((current) => ({ ...current, [selected.id]: result }))
      await refresh()
      setNotice(`${result.length} image series ready`)
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const updateConfiguration = async (values: Parameters<typeof api.configure>[1]) => {
    if (!selected) return
    try {
      const updated = await api.configure(selected.id, values)
      setDatasets((current) => current.map((item) => item.id === updated.id ? updated : item))
      setCropDraft({
        x: updated.cropX,
        y: updated.cropY,
        width: updated.cropWidth,
        height: updated.cropHeight,
      })
      setCropEditing(false)
      setNotice('Crop, scale, and artifact identity updated')
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const beginConversion = async () => {
    if (!selected) return
    try {
      const updated = await api.convert(selected.id)
      setDatasets((current) => current.map((item) => item.id === updated.id ? updated : item))
      setNotice(updated.detail)
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const queueReadySlides = async (ids = datasets.map((item) => item.id)) => {
    const selectedIds = new Set(ids)
    const candidates = datasets.filter((item) => selectedIds.has(item.id) && QUEUEABLE_STATUSES.has(item.status))
    if (!candidates.length) return
    setNotice(`Preparing ${candidates.length} slides for the adaptive queue…`)
    for (const candidate of candidates) {
      try {
        if (candidate.selectedSeries < 0) await api.inspectDataset(candidate.id)
        await api.convert(candidate.id)
      } catch (nextError) {
        setError(`${candidate.displayName}: ${message(nextError)}`)
      }
    }
    await refresh()
  }

  const removeSelectedDatasets = async () => {
    for (const id of batchRemoveIds) await api.deleteDataset(id)
    const remaining = await api.datasets()
    setDatasets(remaining)
    setSelectedDatasetIds([])
    setSelectedId((current) => remaining.some((item) => item.id === current) ? current : remaining[0]?.id || '')
    setBatchRemoveIds([])
    setNotice('Selected slides were removed from the Forge library; originals and completed exports were preserved')
  }

  const syncViewer = async () => {
    setLibraryMode('viewer')
    setNavigatorOpen(true)
    try {
      const next = await api.getViewerConnection()
      setConnection(next)
      if (next.connected) {
        setRemoteLibrary(await api.syncViewerLibrary())
        setRemoteSyncReady(true)
      } else {
        setRemoteSyncReady(false)
      }
      setNotice(next.connected
        ? 'Viewer library synchronized'
        : 'Connect to PathLab Viewer before opening its private library')
      if (!next.connected) connect()
    } catch (nextError) {
      setRemoteSyncReady(false)
      setError(viewerConnectionMessage(nextError))
    }
  }

  useEffect(() => {
    if (libraryMode !== 'viewer' || !connection?.connected) return
    const timer = window.setInterval(() => {
      void api.syncViewerLibrary().then((next) => {
        setRemoteLibrary(next)
        setRemoteSyncReady(true)
      }).catch(() => setRemoteSyncReady(false))
    }, 5_000)
    return () => window.clearInterval(timer)
  }, [libraryMode, connection?.connected])

  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark'
    setTheme(next)
    document.documentElement.dataset.theme = next
    window.localStorage.setItem('pathlab-forge-theme', next)
  }

  const approveCurrent = async () => {
    if (!selected || !currentRevision) return
    try {
      const updated = await api.approve(selected.id, currentRevision.id)
      setDatasets((current) => current.map((item) => item.id === updated.id ? updated : item))
      await refresh()
      setNotice('Exact artifact revision approved for Viewer upload')
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const viewRevision = (revisionId: string) => {
    if (!selected) return
    setViewingRevisionByDataset((current) => ({ ...current, [selected.id]: revisionId }))
    setCropEditing(false)
    window.location.hash = 'dzi-viewer'
    setNotice('Opening saved conversion from History')
  }

  const viewSource = () => {
    if (!selected) return
    setViewingRevisionByDataset((current) => ({ ...current, [selected.id]: '' }))
    setCropEditing(false)
    window.location.hash = 'dzi-viewer'
    setNotice('Opening the original source slide')
  }

  const renameRevision = async (revisionId: string, name: string) => {
    if (!selected) return
    try {
      await api.renameArtifact(selected.id, revisionId, name)
      const result = await api.artifacts(selected.id)
      setArtifactByDataset((current) => ({ ...current, [selected.id]: result.revisions }))
      setNotice('Conversion name saved')
    } catch (nextError) {
      setError(message(nextError))
      throw nextError
    }
  }

  const deleteRevision = async (revisionId: string) => {
    if (!selected) return
    try {
      const updated = await api.deleteArtifact(selected.id, revisionId)
      const result = await api.artifacts(selected.id)
      setDatasets((current) => current.map((item) => item.id === updated.id ? updated : item))
      setArtifactByDataset((current) => ({ ...current, [selected.id]: result.revisions }))
      setViewingRevisionByDataset((current) => ({
        ...current,
        [selected.id]: current[selected.id] === revisionId
          ? ''
          : current[selected.id] || '',
      }))
      setNotice('Saved conversion permanently deleted')
    } catch (nextError) {
      setError(message(nextError))
      throw nextError
    }
  }

  const connect = () => setPairingOpen(true)

  const beginPairing = async () => {
    try {
      setError('')
      const next = await api.startViewerPairing(viewerUrl)
      setPairing(next)
      setNotice(`Waiting for Viewer approval of ${next.userCode}`)
    } catch (nextError) {
      setError(viewerConnectionMessage(nextError))
    }
  }

  useEffect(() => {
    if (!pairing) return undefined
    let stopped = false
    let busy = false
    const poll = async () => {
      if (busy || stopped) return
      busy = true
      try {
        const next = await api.exchangeViewerPairing()
        if (stopped) return
        setConnection(next)
        setViewerUrl(next.viewerUrl)
        setPairing(undefined)
        setPairingOpen(false)
        setNotice('PathLab Viewer connected with a revocable desktop credential')
      } catch (nextError) {
        if (stopped) return
        const normalized = message(nextError).toLowerCase()
        if (!normalized.includes('pairing_pending') && !normalized.includes('not approved yet')) {
          setError(viewerConnectionMessage(nextError))
          setPairing(undefined)
        }
      } finally {
        busy = false
      }
    }
    const timer = window.setInterval(
      () => void poll(),
      Math.max(10, pairing.pollIntervalSeconds * 1_000),
    )
    return () => {
      stopped = true
      window.clearInterval(timer)
    }
  }, [pairing])

  const disconnectViewer = async () => {
    try {
      await api.revokeViewerConnection()
      setConnection(undefined)
      setPairing(undefined)
      setViewerUpload(undefined)
      setPairingOpen(false)
      setNotice('PathLab Viewer disconnected and the desktop credential was revoked')
    } catch (nextError) {
      setError(viewerConnectionMessage(nextError))
    }
  }

  const uploadApproved = async () => {
    if (!selected) return
    if (!connection?.connected) {
      connect()
      return
    }
    try {
      const next = await api.uploadApprovedArtifact(selected.id)
      setViewerUpload(next)
      setNotice(next.detail)
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const createLocalAnnotation = useCallback(async (geometry: string) => {
    if (!selected || cropEditing || ['pan', 'select', 'marquee'].includes(activeTool)) return
    try {
      const created = await api.createAnnotation(selected.id, {
        type: activeTool,
        geometry,
        label: activeTool === 'text' ? 'Text annotation' : '',
      })
      setAnnotationsByDataset((current) => ({
        ...current,
        [selected.id]: [...(current[selected.id] || []), created],
      }))
      setNotice('Annotation saved locally in source-slide coordinates')
    } catch (nextError) {
      setError(message(nextError))
    }
  }, [activeTool, cropEditing, selected?.id])

  const deleteLocalAnnotation = async (annotationId: string) => {
    if (!selected) return
    try {
      await api.deleteAnnotation(selected.id, annotationId)
      setAnnotationsByDataset((current) => ({
        ...current,
        [selected.id]: (current[selected.id] || []).filter(
          (annotation) => annotation.id !== annotationId,
        ),
      }))
      setNotice('Annotation removed from the local draft')
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  useEffect(() => {
    if (!viewerUpload || !['UPLOADING', 'VERIFYING_OME', 'SYNCING_RESULTS', 'RETRYING']
      .includes(viewerUpload.state)) return
    const timer = window.setInterval(() => {
      void api.getViewerUpload().then((next) => {
        setViewerUpload(next)
        setNotice(next.detail)
      }).catch((nextError) => setError(message(nextError)))
    }, 1500)
    return () => window.clearInterval(timer)
  }, [viewerUpload?.state])

  const storage = useMemo(() => ({
    usableBytes: Math.max(0, 512 * 1024 ** 3 - datasets.reduce((sum, item) => sum + item.sourceBytes, 0)),
    effectiveCapacityBytes: 512 * 1024 ** 3,
  }), [datasets])

  const loadFeatures = async (catalogRefresh = false) => {
    setFeatureLoading(true)
    try {
      setFeatures((await api.features(catalogRefresh)).features)
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setFeatureLoading(false)
    }
  }

  const openFeatures = () => {
    setFeatureOpen(true)
    void loadFeatures()
  }

  const changeFeature = async (feature: api.FeaturePack) => {
    setFeatureLoading(true)
    try {
      if (feature.state === 'INSTALLED') await api.disableFeature(feature.id)
      else if (feature.state === 'DISABLED') await api.uninstallFeature(feature.id)
      else await api.installFeature(feature.id)
      setFeatures((await api.features()).features)
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setFeatureLoading(false)
    }
  }

  const rail = (
    <ForgeProductRail
      expanded={railExpanded}
      navigatorOpen={navigatorOpen}
      navigatorButtonRef={navigatorButtonRef}
      storage={storage}
      mode={libraryMode}
      theme={theme}
      onToggleExpanded={() => setRailExpanded((current) => !current)}
      onLocalLibrary={() => {
        setLibraryMode('local')
        setNavigatorOpen(true)
      }}
      onViewerLibrary={() => void syncViewer()}
      onImport={() => setImportOpen(true)}
      onFeatures={openFeatures}
      onTheme={toggleTheme}
      onSecurity={connect}
      onSignOut={connect}
      accountLabel={connection?.connected ? connection.deviceName : 'Viewer account'}
      signOutLabel={connection?.connected ? 'Disconnect' : 'Connect Viewer'}
    />
  )

  return (
    <>
      <div className={[
        'forge-canvas-host',
        navigatorOpen ? '' : 'navigator-collapsed',
        inspectorOpen ? '' : 'inspector-collapsed',
      ].filter(Boolean).join(' ')}>
        <ViewerCanvasShell
          rail={rail}
          railExpanded={railExpanded}
          navigatorOpen={navigatorOpen}
          inspectorOpen={inspectorOpen}
          navigator={(
            <SlideNavigator
              datasets={datasets}
              selectedId={selected?.id || ''}
              mode={libraryMode}
              connection={connection}
              remoteLibrary={remoteLibrary}
              remoteSyncReady={remoteSyncReady}
              checkedIds={selectedDatasetIds}
              folders={localFolders}
              folderByDataset={folderByDataset}
              onSelect={(id) => {
                setSelectedId(id)
                if (window.innerWidth <= 960) setNavigatorOpen(false)
              }}
              onChecked={setSelectedDatasetIds}
              onCreateFolder={(name) => setLocalFolders((current) => current.includes(name) ? current : [...current, name])}
              onMove={(ids, folder) => setFolderByDataset((current) => ({
                ...current,
                ...Object.fromEntries(ids.map((id) => [id, folder])),
              }))}
              onQueue={(ids) => void queueReadySlides(ids)}
              onRemove={(ids) => setBatchRemoveIds(ids)}
              onImport={() => setImportOpen(true)}
              onConnect={connect}
              onSync={() => void syncViewer()}
              onKeepOffline={(id) => void api.keepViewerSlideOffline(id).then(() => {
                setNotice('Offline download started; verified activation will happen in the background')
                window.setTimeout(() => void api.viewerLibrary().then(setRemoteLibrary), 1200)
              }).catch((nextError) => setError(message(nextError)))}
              onRemoveOffline={(id) => void api.removeViewerSlideOffline(id)
                .then(() => api.viewerLibrary()).then(setRemoteLibrary)
                .catch((nextError) => setError(message(nextError)))}
              onRenameRemote={(id, current) => {
                const displayName = window.prompt('Rename private Viewer slide', current)?.trim()
                if (!displayName || displayName === current) return
                void api.updateViewerSlideMetadata(id, { displayName })
                  .then(() => api.syncViewerLibrary()).then(setRemoteLibrary)
                  .catch((nextError) => setError(message(nextError)))
              }}
              selectedRemoteId={selectedRemote?.id || ''}
              onSelectRemote={setSelectedRemoteId}
              onResolveConflict={(id, field, resolution) => void api.resolveViewerConflict(id, field, resolution)
                .then(() => api.syncViewerLibrary()).then(setRemoteLibrary)
                .catch((nextError) => setError(message(nextError)))}
              onCollapse={() => {
                setNavigatorOpen(false)
                window.requestAnimationFrame(() => navigatorButtonRef.current?.focus())
              }}
            />
          )}
          stage={libraryMode === 'viewer' ? (
            <RemoteViewerStage
              slide={selectedRemote}
              viewer={viewer}
              onViewer={setViewer}
              inspectorOpen={inspectorOpen}
              onInspector={() => setInspectorOpen((current) => !current)}
            />
          ) : (
            <ViewerStage
              dataset={selected}
              revision={viewingRevision}
              importing={importing}
              cropBox={cropDraft}
              cropEditing={cropEditing}
              onCropChange={setCropDraft}
              annotations={selectedAnnotations}
              activeTool={activeTool}
              viewer={viewer}
              onViewer={setViewer}
              onCreateAnnotation={createLocalAnnotation}
              inspectorOpen={inspectorOpen}
              onInspector={() => setInspectorOpen((current) => !current)}
            />
          )}
          inspector={libraryMode === 'viewer' ? (
            <RemoteInspector
              slide={selectedRemote}
              folders={remoteLibrary.folders}
              onCollapse={() => setInspectorOpen(false)}
              onKeepOffline={(id) => void api.keepViewerSlideOffline(id)}
              onRemoveOffline={(id) => void api.removeViewerSlideOffline(id)
                .then(() => api.viewerLibrary()).then(setRemoteLibrary)}
              onMove={(id, folderId) => void api.updateViewerSlideMetadata(id, { folderId })
                .then(() => api.syncViewerLibrary()).then(setRemoteLibrary)
                .catch((nextError) => setError(message(nextError)))}
            />
          ) : (
            <Inspector
              dataset={selected}
              series={selectedSeries}
              revisions={revisions}
              annotations={selectedAnnotations}
              activeTool={activeTool}
              cropDraft={cropDraft}
              cropEditing={cropEditing}
              capabilities={capabilities}
              connection={connection}
              viewerUpload={viewerUpload}
              onTool={(tool) => {
                setCropEditing(false)
                setActiveTool(tool)
              }}
              onCropDraft={setCropDraft}
              onCropEditing={(editing) => {
                setCropEditing(editing)
                if (editing) setActiveTool('pan')
              }}
              onCollapse={() => setInspectorOpen(false)}
              onInspect={inspect}
              onConfigure={updateConfiguration}
              onConvert={beginConversion}
              onCancel={() => selected && void api.cancel(selected.id).then(() => refresh())}
              onApprove={approveCurrent}
              onConnect={connect}
              onUpload={uploadApproved}
              onRemove={() => selected && setRemoveTarget(selected)}
              onDeleteAnnotation={deleteLocalAnnotation}
              viewingRevisionId={viewingRevision?.id || ''}
              onViewRevision={viewRevision}
              onViewSource={viewSource}
              onRenameRevision={renameRevision}
              onDeleteRevision={deleteRevision}
            />
          )}
          queue={(
            <QueueDock
              datasets={datasets}
              notice={error || notice}
              isError={Boolean(error)}
              onClearError={() => setError('')}
              onQueueReady={() => void queueReadySlides()}
            />
          )}
        />
      </div>
      {connection?.connected && viewerUpload?.viewerSlideId
        && ['IMAGE_READY', 'SYNCING_RESULTS', 'COMPLETE'].includes(viewerUpload.state) ? (
        <a
          className="forge-viewer-sync-launcher"
          href={`${connection.viewerUrl.replace(/\/$/, '')}/admin/preview/${encodeURIComponent(viewerUpload.viewerSlideId)}`}
          target="_blank"
          rel="noreferrer"
        >
          Open private slide in Viewer
        </a>
      ) : null}
      {viewerUpload && ['UPLOADING', 'VERIFYING_OME', 'RETRYING'].includes(viewerUpload.state) ? (
        <button
          className="forge-viewer-sync-launcher"
          type="button"
          onClick={() => void api.cancelViewerUpload()
            .then((next) => { setViewerUpload(next); setNotice(next.detail) })
            .catch((nextError) => setError(message(nextError)))}
        >
          Cancel Viewer delivery
        </button>
      ) : null}
      {importOpen ? (
        <ImportDialog
          path={importPath}
          onPath={setImportPath}
          onChoose={() => void handleNativeImport()}
          onChooseFolder={() => void handleProjectImport()}
          onImport={() => void handlePathImport()}
          onImportFolder={() => void handleProjectImport(importPath)}
          onClose={() => setImportOpen(false)}
        />
      ) : null}
      {removeTarget ? (
        <RemoveDatasetDialog
          dataset={removeTarget}
          onRemove={() => void removeDataset()}
          onClose={() => setRemoveTarget(undefined)}
        />
      ) : null}
      {pairingOpen ? (
        <ViewerPairingDialog
          viewerUrl={viewerUrl}
          pairing={pairing}
          connection={connection}
          onViewerUrl={setViewerUrl}
          onStart={() => void beginPairing()}
          onDisconnect={() => void disconnectViewer()}
          onClose={() => setPairingOpen(false)}
        />
      ) : null}
      {batchRemoveIds.length ? (
        <BatchRemoveDialog
          count={batchRemoveIds.length}
          onRemove={() => void removeSelectedDatasets().catch((nextError) => setError(message(nextError)))}
          onClose={() => setBatchRemoveIds([])}
        />
      ) : null}
      {featureOpen ? (
        <FeatureCenter
          features={features}
          loading={featureLoading}
          onRefresh={() => void loadFeatures(true)}
          onChange={(feature) => void changeFeature(feature)}
          onClose={() => setFeatureOpen(false)}
        />
      ) : null}
    </>
  )
}

function ForgeProductRail({
  expanded,
  navigatorOpen,
  navigatorButtonRef,
  storage,
  mode,
  theme,
  onToggleExpanded,
  onLocalLibrary,
  onViewerLibrary,
  onImport,
  onFeatures,
  onTheme,
  onSecurity,
  onSignOut,
  accountLabel,
  signOutLabel,
}: {
  expanded: boolean
  navigatorOpen: boolean
  navigatorButtonRef: Ref<HTMLButtonElement>
  storage: { usableBytes: number; effectiveCapacityBytes: number }
  mode: 'local' | 'viewer'
  theme: 'light' | 'dark'
  onToggleExpanded: () => void
  onLocalLibrary: () => void
  onViewerLibrary: () => void
  onImport: () => void
  onFeatures: () => void
  onTheme: () => void
  onSecurity: () => void
  onSignOut: () => void
  accountLabel: string
  signOutLabel: string
}) {
  const remaining = storage.effectiveCapacityBytes > 0
    ? Math.round(storage.usableBytes / storage.effectiveCapacityBytes * 100)
    : 0
  return (
    <aside className="library-app-rail" aria-label="Product navigation" data-canvas-region="icon-rail">
      <div className="library-rail-brand">
        <div className="brand brand-library" aria-label="PathLab Forge">
          <span className="brand-mark brand-mark-forge">
            <svg aria-hidden="true" viewBox="0 0 32 32" fill="none">
              <path d="M7 5.5h9.8c5.6 0 8.7 2.7 8.7 7.3 0 4.8-3.4 7.7-9.2 7.7h-4.1V27H7V5.5Z" fill="currentColor" opacity=".34" />
              <path d="M10 5.5v21.2M10 8h7c3.5 0 5.6 1.7 5.6 4.8 0 3.2-2.2 5.1-5.8 5.1H10" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="3" />
              <path d="m19.7 20.1 4.8 2.4-4.8 2.4-4.8-2.4 4.8-2.4Z" fill="currentColor" />
            </svg>
          </span>
          <span>PathLab</span><span className="brand-product">Forge</span>
        </div>
      </div>
      <button className="library-rail-toggle" type="button" aria-label={expanded ? 'Collapse navigation rail' : 'Expand navigation rail'} aria-expanded={expanded} onClick={onToggleExpanded}>
        <SidebarSimple aria-hidden="true" /><span>{expanded ? 'Collapse' : 'Expand'}</span>
      </button>
      <nav className="library-rail-primary" aria-label="Library destinations">
        <button ref={navigatorButtonRef} className={mode === 'local' && navigatorOpen ? 'active' : ''} type="button" aria-label="Local library" aria-expanded={mode === 'local' && navigatorOpen} onClick={onLocalLibrary}>
          <List aria-hidden="true" /><span>Local library</span>
        </button>
        <button className={mode === 'viewer' && navigatorOpen ? 'active' : ''} type="button" aria-label="Viewer library" aria-expanded={mode === 'viewer' && navigatorOpen} onClick={onViewerLibrary}>
          <CloudArrowUp aria-hidden="true" /><span>Viewer library</span>
        </button>
        <button type="button" aria-label="Import" onClick={onImport}><UploadSimple aria-hidden="true" /><span>Import</span></button>
        <button type="button" aria-label="Feature Center" onClick={onFeatures}><Wrench aria-hidden="true" /><span>Feature Center</span></button>
      </nav>
      <div className="library-rail-utilities" aria-label="Account actions">
        <section className="library-storage-meter" aria-label={`Storage, ${formatBytes(storage.usableBytes)} available`}>
          <div className="library-storage-copy"><span>Storage</span><strong>{formatBytes(storage.usableBytes)} available</strong></div>
          <div className="library-storage-track" role="meter" aria-label="Usable storage remaining" aria-valuemin={0} aria-valuemax={100} aria-valuenow={remaining}><span style={{ width: `${remaining}%` }} /></div>
        </section>
        <button type="button" aria-label={`${theme === 'dark' ? 'Dark' : 'Light'} theme. Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`} onClick={onTheme}>
          {theme === 'dark' ? <Moon aria-hidden="true" /> : <Sun aria-hidden="true" />}<span>{theme === 'dark' ? 'Dark theme' : 'Light theme'}</span>
        </button>
        <button type="button" aria-label={accountLabel} onClick={onSecurity}><Key aria-hidden="true" /><span>{accountLabel}</span></button>
        <button type="button" aria-label={signOutLabel} onClick={onSignOut}><SignOut aria-hidden="true" /><span>{signOutLabel}</span></button>
      </div>
    </aside>
  )
}

function FeatureCenter({
  features,
  loading,
  onRefresh,
  onChange,
  onClose,
}: {
  features: api.FeaturePack[]
  loading: boolean
  onRefresh: () => void
  onChange: (feature: api.FeaturePack) => void
  onClose: () => void
}) {
  return (
    <div className="forge-dialog-backdrop" role="presentation">
      <section className="forge-connect-dialog forge-feature-center" role="dialog" aria-modal="true" aria-labelledby="feature-center-title">
        <span>Optional capabilities</span>
        <h2 id="feature-center-title">Feature Center</h2>
        <p>Forge stays small. Approved pathology and research tools install only when requested.</p>
        <div className="forge-feature-list">
          {features.map((feature) => (
            <article key={feature.id}>
              <div>
                <small>{feature.kind}{feature.pretrained ? ' · pretrained' : ''}{feature.trainingOnly ? ' · training only' : ''}</small>
                <strong>{feature.name}</strong>
                <p>{feature.detail}</p>
                {feature.downloadBytes ? <span>{formatBytes(feature.downloadBytes)} download · {formatBytes(feature.installedBytes)} installed</span> : null}
              </div>
              <button
                type="button"
                disabled={loading || !['AVAILABLE', 'INSTALLED', 'DISABLED'].includes(feature.state)}
                onClick={() => onChange(feature)}
              >
                {feature.state === 'INSTALLED' ? 'Disable' : feature.state === 'DISABLED' ? 'Uninstall' : feature.state === 'AVAILABLE' ? 'Install' : feature.state.replaceAll('_', ' ').toLowerCase()}
              </button>
            </article>
          ))}
          {!features.length ? <p role="status">{loading ? 'Checking installed features…' : 'No features are published.'}</p> : null}
        </div>
        <button type="button" disabled={loading} onClick={onRefresh}>Refresh signed catalog</button>
        <small>No catalog request is made during normal startup.</small>
        <button className="forge-dialog-close" type="button" onClick={onClose}>Close</button>
      </section>
    </div>
  )
}

function RemoveDatasetDialog({
  dataset,
  onRemove,
  onClose,
}: {
  dataset: Dataset
  onRemove: () => void
  onClose: () => void
}) {
  return (
    <div className="forge-dialog-backdrop">
      <section className="forge-connect-dialog" role="dialog" aria-modal="true" aria-labelledby="forge-remove-title">
        <span>Local slide library</span>
        <h2 id="forge-remove-title">Remove slide?</h2>
        <p><strong>{dataset.displayName}</strong> will disappear from this Forge library.</p>
        <p>The original VSI or OME-TIFF and any completed exports remain on disk.</p>
        <button className="forge-danger" type="button" onClick={onRemove}><Trash /> Remove from Forge</button>
        <button className="forge-dialog-close" type="button" onClick={onClose}>Cancel</button>
      </section>
    </div>
  )
}

function ImportDialog({
  path,
  onPath,
  onChoose,
  onChooseFolder,
  onImport,
  onImportFolder,
  onClose,
}: {
  path: string
  onPath: (value: string) => void
  onChoose: () => void
  onChooseFolder: () => void
  onImport: () => void
  onImportFolder: () => void
  onClose: () => void
}) {
  return (
    <div className="forge-dialog-backdrop">
      <section className="forge-connect-dialog" role="dialog" aria-modal="true" aria-labelledby="forge-import-title">
        <span>Local pathology project</span>
        <h2 id="forge-import-title">Import slides</h2>
        <p>Select several SVS/OME-TIFF/VSI files, or recursively discover a project folder. VSI companion ETS files are grouped automatically.</p>
        <button className="forge-primary" type="button" onClick={onChoose}>Choose slide files…</button>
        <button type="button" onClick={onChooseFolder}>Choose project folder…</button>
        <div className="forge-dialog-divider"><span>or enter its full local path</span></div>
        <label>
          Local slide path
          <input
            type="text"
            value={path}
            onChange={(event) => onPath(event.target.value)}
            placeholder="C:\path\slide.svs"
          />
        </label>
        <div className="forge-dialog-actions">
          <button type="button" disabled={!path.trim()} onClick={onImport}>Import this path</button>
          <button type="button" disabled={!path.trim()} onClick={onImportFolder}>Import folder path</button>
        </div>
        <button className="forge-dialog-close" type="button" onClick={onClose}>Cancel</button>
      </section>
    </div>
  )
}

function ViewerPairingDialog({
  viewerUrl,
  pairing,
  connection,
  onViewerUrl,
  onStart,
  onDisconnect,
  onClose,
}: {
  viewerUrl: string
  pairing?: ViewerPairing
  connection?: ViewerConnection
  onViewerUrl: (value: string) => void
  onStart: () => void
  onDisconnect: () => void
  onClose: () => void
}) {
  const [confirmingDisconnect, setConfirmingDisconnect] = useState(false)
  const [advanced, setAdvanced] = useState(false)
  const connected = Boolean(connection?.connected)
  const qr = useMemo(
    () => pairing ? renderSVG(pairing.verificationUrlComplete, {
      ecc: 'M', border: 3, pixelSize: 4, blackColor: '#181713', whiteColor: '#ffffff',
    }) : '',
    [pairing],
  )
  return (
    <div className="forge-dialog-backdrop">
      <section className="forge-connect-dialog" role="dialog" aria-modal="true" aria-labelledby="forge-connect-title">
        <span>PathLab Viewer</span>
        <h2 id="forge-connect-title">{connected ? 'Viewer connection' : 'Connect to Viewer'}</h2>
        {connected ? (
          <>
            <p>This device has a private, revocable Viewer credential.</p>
            <dl className="forge-connection-details">
              <div><dt>Viewer URL</dt><dd>{connection?.viewerUrl}</dd></div>
              <div><dt>Device</dt><dd>{connection?.deviceName}</dd></div>
              <div><dt>Scopes</dt><dd>{connection?.scopes.map((scope) => <span key={scope}>{scope}</span>)}</dd></div>
            </dl>
            {!confirmingDisconnect ? (
              <button className="forge-danger" type="button" onClick={() => setConfirmingDisconnect(true)}>
                Disconnect this device
              </button>
            ) : (
              <div role="alert">
                <p>Viewer uploads stop and this device credential will be revoked.</p>
                <button className="forge-danger" type="button" onClick={onDisconnect}>Confirm disconnect</button>
                <button type="button" onClick={() => setConfirmingDisconnect(false)}>Keep connected</button>
              </div>
            )}
          </>
        ) : !pairing ? (
          <>
            <p>Approve this device in Viewer. No VPN, port forwarding, or firewall setup is needed.</p>
            {advanced ? (
              <label>
                Viewer address
                <input
                  type="url"
                  value={viewerUrl}
                  onChange={(event) => onViewerUrl(event.target.value)}
                  placeholder="https://viewer.example"
                />
              </label>
            ) : null}
            <button className="forge-primary" type="button" onClick={onStart}>Connect to PathLab Viewer</button>
            <button type="button" onClick={() => setAdvanced((value) => !value)}>Advanced connection</button>
          </>
        ) : (
          <>
            <div className="forge-pairing-code"><span>Verification code</span><strong>{pairing.userCode}</strong></div>
            <div
              className="forge-pairing-qr"
              role="img"
              aria-label="Scan to approve this Forge device"
              data-value={pairing.verificationUrlComplete}
              dangerouslySetInnerHTML={{ __html: qr }}
            />
            <a className="forge-primary" href={pairing.verificationUrlComplete} target="_blank" rel="noreferrer">
              Open Viewer approval
            </a>
            <small>Waiting for approval · expires {new Date(pairing.expiresAt).toLocaleTimeString()}</small>
          </>
        )}
        <button className="forge-dialog-close" type="button" onClick={onClose}>Cancel</button>
      </section>
    </div>
  )
}

function SlideNavigator({
  datasets,
  selectedId,
  mode,
  connection,
  remoteLibrary,
  remoteSyncReady,
  checkedIds,
  folders,
  folderByDataset,
  onSelect,
  onChecked,
  onCreateFolder,
  onMove,
  onQueue,
  onRemove,
  onImport,
  onConnect,
  onSync,
  onKeepOffline,
  onRemoveOffline,
  onRenameRemote,
  selectedRemoteId,
  onSelectRemote,
  onResolveConflict,
  onCollapse,
}: {
  datasets: Dataset[]
  selectedId: string
  mode: 'local' | 'viewer'
  connection?: ViewerConnection
  remoteLibrary: api.ViewerRemoteLibrary
  remoteSyncReady: boolean
  checkedIds: string[]
  folders: string[]
  folderByDataset: Record<string, string>
  onSelect: (id: string) => void
  onChecked: (ids: string[]) => void
  onCreateFolder: (name: string) => void
  onMove: (ids: string[], folder: string) => void
  onQueue: (ids: string[]) => void
  onRemove: (ids: string[]) => void
  onImport: () => void
  onConnect: () => void
  onSync: () => void
  onKeepOffline: (id: string) => void
  onRemoveOffline: (id: string) => void
  onRenameRemote: (id: string, current: string) => void
  selectedRemoteId: string
  onSelectRemote: (id: string) => void
  onResolveConflict: (id: string, field: string, resolution: 'local' | 'viewer') => void
  onCollapse: () => void
}) {
  const [query, setQuery] = useState('')
  const [activeFolder, setActiveFolder] = useState('All slides')
  const [newFolder, setNewFolder] = useState('')
  const checked = new Set(checkedIds)
  const visible = datasets.filter((dataset) => {
    const matchesSearch = dataset.displayName.toLowerCase().includes(query.trim().toLowerCase())
    const folder = folderByDataset[dataset.id] || DEFAULT_LOCAL_FOLDER
    return matchesSearch && (activeFolder === 'All slides' || folder === activeFolder)
  })
  const toggle = (id: string) => onChecked(checked.has(id)
    ? checkedIds.filter((current) => current !== id)
    : [...checkedIds, id])

  if (mode === 'viewer') {
    return (
      <div className="forge-navigator forge-viewer-library">
        <header>
          <div><span>Connected workspace</span><strong>Viewer library</strong></div>
          <button type="button" aria-label="Collapse Viewer library" onClick={onCollapse}><SidebarSimple /></button>
        </header>
        <div className="forge-viewer-library-status">
          <span className={connection?.connected ? 'connected' : ''} />
          <strong>{connection?.connected ? connection.deviceName : 'Viewer not connected'}</strong>
          <small>{connection?.connected && remoteSyncReady ? `${remoteLibrary.items.length} private slides · hybrid offline mode` : connection?.connected ? 'Viewer connected · sync API unavailable' : 'Connect once to synchronize your private library'}</small>
        </div>
        <button className="forge-sync-viewer" type="button" aria-label={connection?.connected ? 'Refresh Viewer connection' : 'Connect to Viewer'} onClick={connection?.connected ? onSync : onConnect}>
          <ArrowsClockwise /> {connection?.connected ? 'Sync changes' : 'Connect to Viewer'}
        </button>
        {connection?.connected && remoteSyncReady ? (
          <div className="forge-viewer-sync-boundary" role="status">
            <strong>Two-way sync active</strong>
            <span>Thumbnails stream through Forge. Choose Keep offline for a verified full OME copy. Conflicting edits pause per field.</span>
          </div>
        ) : connection?.connected ? <div className="forge-viewer-sync-boundary" role="status"><strong>Viewer update required</strong><span>Restart Viewer with the matching desktop-sync/v1 build, then choose Sync changes.</span></div> : null}
        <nav aria-label="Viewer folders">
          {remoteLibrary.folders.map((folder) => (
            <button type="button" key={folder.id}><Folder /><span><strong>{folder.name}</strong><small>Private folder</small></span></button>
          ))}
        </nav>
        <section className="forge-remote-slides" aria-label="Synchronized Viewer slides">
          {remoteLibrary.items.map((item) => (
            <article key={item.id} className={`forge-remote-slide ${selectedRemoteId === item.id ? 'active' : ''}`} onClick={() => onSelectRemote(item.id)}>
              <img src={item.thumbnailUrl} alt="" loading="lazy" />
              <span><strong>{item.displayName}</strong><small>{item.offlineComplete ? 'Available offline' : formatBytes(item.contentBytes)}</small></span>
              <button type="button" onClick={() => item.offlineComplete ? onRemoveOffline(item.id) : onKeepOffline(item.id)}>
                {item.offlineComplete ? 'Remove offline copy' : 'Keep offline'}
              </button>
              <button type="button" onClick={() => onRenameRemote(item.id, item.displayName)}>Rename</button>
            </article>
          ))}
          {connection?.connected && remoteLibrary.items.length === 0 ? <p>No private Viewer slides yet.</p> : null}
        </section>
        {remoteLibrary.conflicts.map((conflict) => <div className="forge-viewer-sync-boundary forge-conflict" key={`${conflict.slideId}:${conflict.field}`}><strong>Resolve {conflict.field}</strong><span>Both versions are preserved.</span><button type="button" onClick={() => onResolveConflict(conflict.slideId, conflict.field, 'local')}>Keep local</button><button type="button" onClick={() => onResolveConflict(conflict.slideId, conflict.field, 'viewer')}>Keep Viewer</button></div>)}
      </div>
    )
  }

  return (
    <div className="forge-navigator">
      <header>
        <div><span>Local workspace</span><strong>Local library</strong></div>
        <div className="forge-navigator-actions">
          <button
            type="button"
            aria-label="Collapse slide library"
            title="Collapse slide library"
            onClick={onCollapse}
          >
            <SidebarSimple />
          </button>
          <button type="button" onClick={onImport}><FolderOpen /> Import</button>
        </div>
      </header>
      <label className="forge-search">
        <span className="visually-hidden">Search local slides</span>
        <input type="search" placeholder="Search slides and folders" value={query} onChange={(event) => setQuery(event.target.value)} />
      </label>
      <section className="forge-local-folders" aria-label="Local folders">
        <div className="forge-folder-actions">
          <button type="button" onClick={() => setNewFolder((current) => current ? '' : 'New folder')}><Plus /> New folder</button>
          <button type="button" onClick={onSync}><ArrowsClockwise /> Open Viewer</button>
        </div>
        {newFolder ? (
          <form onSubmit={(event) => { event.preventDefault(); const name = newFolder.trim(); if (name) { onCreateFolder(name); setActiveFolder(name); setNewFolder('') } }}>
            <input aria-label="New folder name" autoFocus value={newFolder} onChange={(event) => setNewFolder(event.target.value)} />
            <button type="submit">Add</button>
          </form>
        ) : null}
        <div className="forge-folder-tree">
          {['All slides', ...folders].map((folder) => (
            <button className={activeFolder === folder ? 'active' : ''} type="button" key={folder} onClick={() => setActiveFolder(folder)}>
              <Folder /> <span>{folder}</span><small>{folder === 'All slides' ? datasets.length : datasets.filter((item) => (folderByDataset[item.id] || DEFAULT_LOCAL_FOLDER) === folder).length}</small>
            </button>
          ))}
        </div>
      </section>
      {checkedIds.length ? (
        <div className="forge-batch-actions" role="toolbar" aria-label={`${checkedIds.length} selected slides`}>
          <strong>{checkedIds.length} selected</strong>
          <button type="button" onClick={() => onQueue(checkedIds)}>Queue</button>
          <label>Move<span className="visually-hidden"> selected slides to folder</span>
            <select defaultValue="" onChange={(event) => { if (event.target.value) onMove(checkedIds, event.target.value); event.target.value = '' }}>
              <option value="" disabled>Move…</option>{folders.map((folder) => <option value={folder} key={folder}>{folder}</option>)}
            </select>
          </label>
          <button className="danger" type="button" onClick={() => onRemove(checkedIds)}><Trash /> Remove</button>
        </div>
      ) : null}
      <nav className="forge-slide-list" aria-label="Local slides">
        {visible.length ? visible.map((dataset) => {
          const progress = libraryProgress(dataset)
          const thumbnailSeries = Math.max(0, dataset.selectedSeries)
          return (
          <div key={dataset.id} className={`forge-slide-row${dataset.id === selectedId ? ' active' : ''}`}>
            <label className="forge-check">
              <input type="checkbox" aria-label={`Select ${dataset.displayName}`} checked={checked.has(dataset.id)} onChange={() => toggle(dataset.id)} />
              <span className="forge-check-control" aria-hidden="true"><CheckCircle /></span>
            </label>
            <button type="button" onClick={() => onSelect(dataset.id)}>
              <span className="forge-slide-thumbnail"><img src={`/api/datasets/${encodeURIComponent(dataset.id)}/series/${thumbnailSeries}/thumbnail?v=${encodeURIComponent(dataset.sourceFingerprint.slice(0, 24))}`} alt="" loading="lazy" onError={(event) => { event.currentTarget.hidden = true }} /></span>
              <span className="forge-slide-copy">
                <strong>{dataset.displayName}</strong>
                <small><i className={`forge-slide-dot status-${dataset.status.toLowerCase()}`} />{statusLabel(dataset.status)}</small>
                <span className="forge-slide-progress"><progress aria-label={`${dataset.displayName} library conversion progress`} max="100" value={progress} /><b>{progress}%</b></span>
              </span>
            </button>
          </div>
        )}) : datasets.length ? (
          <div className="forge-empty-nav"><Folder /><strong>No slides in this folder</strong><span>Move slides here using the selection toolbar.</span></div>
        ) : (
          <div className="forge-empty-nav">
            <Crosshair aria-hidden="true" />
            <strong>No local slides</strong>
            <span>Import an SVS, OME-TIFF or VSI; Forge finds matching VSI companions.</span>
          </div>
        )}
      </nav>
    </div>
  )
}

function RemoteViewerStage({ slide, viewer, onViewer, inspectorOpen, onInspector }: {
  slide?: api.ViewerRemoteItem
  viewer: OpenSeadragon.Viewer | null
  onViewer: (viewer: OpenSeadragon.Viewer | null) => void
  inspectorOpen: boolean
  onInspector: () => void
}) {
  return (
    <section id="dzi-viewer" className="forge-stage" aria-label="Whole-slide viewer">
      <header className="forge-viewer-header">
        <div><strong>{slide?.displayName || 'Viewer library'}</strong><span>{slide ? 'Private Viewer slide · authenticated tile cache' : 'Choose a synchronized slide'}</span></div>
        <button type="button" aria-label={inspectorOpen ? 'Collapse slide inspector' : 'Open slide inspector'} aria-expanded={inspectorOpen} onClick={onInspector}><SidebarSimple /></button>
      </header>
      {slide ? <SlideViewer tileSource={slide.tileSourceUrl} sourceWidth={slide.width} sourceHeight={slide.height} onReady={onViewer} /> : (
        <div className="forge-stage-empty"><CloudArrowUp /><h1>No synchronized slides</h1><p>Sync a matching Viewer build to browse private slides here.</p></div>
      )}
      <div className="forge-viewer-tools" aria-label="Viewer controls">
        <button type="button" aria-label="Zoom out" disabled={!viewer} onClick={() => viewer?.viewport.zoomBy(.67)}><MagnifyingGlassMinus /></button>
        <button type="button" aria-label="Home" disabled={!viewer} onClick={() => viewer?.viewport.goHome()}><House /></button>
        <button type="button" aria-label="Zoom in" disabled={!viewer} onClick={() => viewer?.viewport.zoomBy(1.5)}><MagnifyingGlassPlus /></button>
        <button type="button" aria-label="Full screen" disabled={!viewer} onClick={() => viewer?.setFullScreen(!viewer.isFullPage())}><ArrowsOut /></button>
      </div>
    </section>
  )
}

function RemoteInspector({ slide, folders, onCollapse, onKeepOffline, onRemoveOffline, onMove }: {
  slide?: api.ViewerRemoteItem
  folders: api.ViewerRemoteLibrary['folders']
  onCollapse: () => void
  onKeepOffline: (id: string) => void
  onRemoveOffline: (id: string) => void
  onMove: (id: string, folderId: string) => void
}) {
  return (
    <aside id="forge-slide-inspector" className="forge-inspector" aria-label="Viewer slide inspector">
      <header><div><span>Viewer slide</span><h2>{slide?.displayName || 'No slide selected'}</h2></div><button type="button" aria-label="Collapse slide inspector" onClick={onCollapse}><SidebarSimple /></button></header>
      {slide ? <section className="forge-inspector-section">
        <strong>Private synchronized record</strong>
        <small>{formatBytes(slide.contentBytes)} · {slide.state.replaceAll('_', ' ')}</small>
        <label>Viewer folder<select value={slide.folderId} onChange={(event) => onMove(slide.id, event.target.value)}><option value="">Unfiled</option>{folders.map((folder) => <option key={folder.id} value={folder.id}>{folder.name}</option>)}</select></label>
        <button type="button" onClick={() => slide.offlineComplete ? onRemoveOffline(slide.id) : onKeepOffline(slide.id)}>{slide.offlineComplete ? 'Remove offline copy' : 'Keep verified OME offline'}</button>
      </section> : null}
    </aside>
  )
}

function ViewerStage({
  dataset,
  revision,
  importing,
  cropBox,
  cropEditing,
  onCropChange,
  annotations,
  activeTool,
  viewer,
  onViewer,
  onCreateAnnotation,
  inspectorOpen,
  onInspector,
}: {
  dataset?: Dataset
  revision?: ArtifactRevision
  importing: boolean
  cropBox?: CropBox
  cropEditing: boolean
  onCropChange: (box: CropBox) => void
  annotations: AnnotationRecord[]
  activeTool: string
  viewer: OpenSeadragon.Viewer | null
  onViewer: (viewer: OpenSeadragon.Viewer | null) => void
  onCreateAnnotation: (geometry: string) => void
  inspectorOpen: boolean
  onInspector: () => void
}) {
  const previewIdentity = dataset?.configurationRevision || String(dataset?.selectedSeries ?? '')
  const showingConvertedResult = Boolean(
    dataset && revision && ['READY', 'APPROVED'].includes(revision.status),
  )
  const converting = Boolean(dataset && CONVERSION_STATUSES.has(dataset.status))
  const inspecting = dataset?.status === 'INSPECTING'
  const tileSource = showingConvertedResult && !cropEditing && dataset && revision
    ? revision.format === 'OME_DYNAMIC_V1'
      ? api.artifactOmePreviewUrl(dataset.id, revision.id)
      : api.artifactDziUrl(dataset.id, revision.id)
    : dataset && dataset.selectedSeries >= 0 && [
        'READY_TO_CONVERT',
        'PACKAGE_READY',
        'CONVERSION_READY',
        'READY',
        'APPROVED',
        'FAILED',
        'CANCELLED',
        'QUEUED',
        'WAITING_RESOURCES',
        ...CONVERSION_STATUSES,
      ].includes(dataset.status)
      ? `/api/datasets/${encodeURIComponent(dataset.id)}/preview/slide.dzi?revision=${encodeURIComponent(previewIdentity)}&preview=${DIRECT_PREVIEW_VERSION}`
      : ''
  return (
    <section id="dzi-viewer" className="forge-stage" aria-label="Whole-slide viewer">
      <header className="forge-viewer-header">
        <div>
          <strong>{revision?.name || dataset?.displayName || 'PathLab Forge viewer'}</strong>
          <span>{dataset ? `${datasetFormatLabel(dataset.format)} · ${statusLabel(dataset.status)} · ${showingConvertedResult ? 'Converted result' : converting ? 'Viewer unlocks after validation' : 'Original source viewer'}` : 'Choose a local slide from the panel'}</span>
        </div>
        <button
          type="button"
          aria-label={inspectorOpen ? 'Collapse slide inspector' : 'Open slide inspector'}
          aria-expanded={inspectorOpen}
          aria-controls="forge-slide-inspector"
          title={inspectorOpen ? 'Collapse slide inspector' : 'Open slide inspector'}
          onClick={onInspector}
        >
          <SidebarSimple />
        </button>
      </header>
      {importing ? (
        <PreviewLoading
          title="Preparing imported slide"
          detail="Verifying the source and companion files before the viewer opens…"
        />
      ) : tileSource ? (
        <SlideViewer
          tileSource={tileSource}
          activeTool={activeTool}
          cropBox={cropBox}
          cropEditing={cropEditing}
          onCropChange={onCropChange}
          annotations={annotations}
          sourceWidth={dataset?.width || 1}
          sourceHeight={dataset?.height || 1}
          cropX={revision && ['READY', 'APPROVED'].includes(revision.status)
            ? revision.cropX ?? dataset?.cropX ?? 0
            : 0}
          cropY={revision && ['READY', 'APPROVED'].includes(revision.status)
            ? revision.cropY ?? dataset?.cropY ?? 0
            : 0}
          downsample={revision && ['READY', 'APPROVED'].includes(revision.status)
            ? revision.downsample ?? dataset?.downsample ?? 1
            : 0}
          onCreate={onCreateAnnotation}
          onReady={onViewer}
        />
      ) : inspecting && dataset ? (
        <PreviewLoading
          title="Opening slide"
          detail="Reading the image series and preparing the first visible tile…"
        />
      ) : (
        <div className="forge-stage-empty">
          <span className="forge-tissue-mark"><Crosshair /></span>
          <h1>{dataset ? 'Preparing slide preview' : 'Your slides, ready at launch'}</h1>
          <p>{dataset ? 'Inspect the image series, set a crop and scale, then convert. The exact result opens here before approval or upload.' : 'Import an SVS, OME-TIFF or VSI. The slide panel remains visible so image-series selection and conversion feel like one viewer workflow.'}</p>
        </div>
      )}
      {converting && dataset ? (
        <ConversionProgress dataset={dataset} revision={revision} />
      ) : null}
      <div className="forge-viewer-tools" aria-label="Viewer controls">
        <button type="button" aria-label="Zoom out" disabled={!viewer || converting} onClick={() => viewer?.viewport.zoomBy(.67)}><MagnifyingGlassMinus /></button>
        <button type="button" aria-label="Home" disabled={!viewer || converting} onClick={() => viewer?.viewport.goHome()}><House /></button>
        <button type="button" aria-label="Zoom in" disabled={!viewer || converting} onClick={() => viewer?.viewport.zoomBy(1.5)}><MagnifyingGlassPlus /></button>
        <button type="button" aria-label="Full screen" disabled={!viewer || converting} onClick={() => viewer?.setFullScreen(!viewer.isFullPage())}><ArrowsOut /></button>
      </div>
    </section>
  )
}

function PreviewLoading({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="forge-preview-loading" role="status" aria-live="polite">
      <span aria-hidden="true" />
      <strong>{title}</strong>
      <small>{detail}</small>
    </div>
  )
}

function ConversionProgress({ dataset, revision }: { dataset: Dataset; revision?: ArtifactRevision }) {
  const directOme = revision?.format === 'OME_DYNAMIC_V1' || dataset.stage === 'DIRECT_OME'
  const phase = conversionPhase(dataset, directOme)
  const routeLabel = conversionRouteLabel(dataset, directOme)
  const stages = directOme
    ? ['Rendering OME-TIFF', 'Validating OME-TIFF', 'Ready for review']
    : ['Read source', 'Encode & validate', 'Package']
  const fallbackElapsed = useElapsed(revision?.createdAt)
  const elapsed = dataset.elapsedMs
    ? formatDuration(dataset.elapsedMs)
    : fallbackElapsed
  const remaining = dataset.estimatedRemainingMs
    ? formatDuration(dataset.estimatedRemainingMs)
    : ''
  const counter = conversionCounter(dataset)
  const filledTiles = Math.max(1, Math.round(48 * phase.percent / 100))
  const indeterminate = phase.indeterminate === true
  return (
    <div className="forge-conversion-progress" aria-live="polite">
      <div className="forge-conversion-heading">
        <span className="forge-conversion-kicker">{routeLabel}</span>
        <span>{dataset.resourceProfile?.replace('adaptive-', '').replaceAll('-', ' · ') || 'minimum-safe profile'}</span>
      </div>
      <div className="forge-tile-reader" aria-hidden="true">
        <div className="forge-tile-reader-grid">
          {Array.from({ length: 48 }, (_, index) => (
            <i
              className={index < filledTiles ? 'read' : index === filledTiles ? 'reading' : ''}
              key={index}
            />
          ))}
        </div>
        <span className="forge-tile-reader-beam" />
        <small>{counter}</small>
      </div>
      <strong>{phase.label}</strong>
      <p>{dataset.detail}</p>
      <progress aria-label="Conversion progress" max="100" value={indeterminate ? undefined : phase.percent} />
      <div className="forge-conversion-progress-copy">
        <span>Step {phase.step} of {stages.length}</span>
        <span>{indeterminate ? 'Finalizing…' : `${phase.percent}%`}</span>
      </div>
      <ol aria-label="Conversion stages">
        {stages.map((label, index) => (
          <li className={index + 1 < phase.step ? 'complete' : index + 1 === phase.step ? 'active' : ''} key={label}>
            <i />
            <span>{label}</span>
          </li>
        ))}
      </ol>
      <small>
        Elapsed {elapsed}
        {remaining ? ` · about ${remaining} left in this phase` : ''}
        {dataset.unitsPerSecond ? ` · ${formatRate(dataset.unitsPerSecond, dataset.stage)}` : ''}
      </small>
    </div>
  )
}

function Inspector({
  dataset,
  series,
  revisions,
  annotations,
  activeTool,
  cropDraft,
  cropEditing,
  capabilities,
  connection,
  viewerUpload,
  onTool,
  onCropDraft,
  onCropEditing,
  onCollapse,
  onInspect,
  onConfigure,
  onConvert,
  onCancel,
  onApprove,
  onConnect,
  onUpload,
  onRemove,
  onDeleteAnnotation,
  viewingRevisionId,
  onViewRevision,
  onViewSource,
  onRenameRevision,
  onDeleteRevision,
}: {
  dataset?: Dataset
  series: SeriesInfo[]
  revisions: ArtifactRevision[]
  annotations: AnnotationRecord[]
  activeTool: string
  cropDraft?: CropBox
  cropEditing: boolean
  capabilities?: Awaited<ReturnType<typeof api.capabilities>>
  connection?: ViewerConnection
  viewerUpload?: api.ViewerUpload
  onTool: (tool: string) => void
  onCropDraft: (box: CropBox) => void
  onCropEditing: (editing: boolean) => void
  onCollapse: () => void
  onInspect: () => void
  onConfigure: (values: Parameters<typeof api.configure>[1]) => Promise<void>
  onConvert: () => void
  onCancel: () => void
  onApprove: () => void
  onConnect: () => void
  onUpload: () => void
  onRemove: () => void
  onDeleteAnnotation: (annotationId: string) => void
  viewingRevisionId: string
  onViewRevision: (revisionId: string) => void
  onViewSource: () => void
  onRenameRevision: (revisionId: string, name: string) => Promise<void>
  onDeleteRevision: (revisionId: string) => Promise<void>
}) {
  const [section, setSection] = useState<'export' | 'annotations' | 'history'>('export')
  if (!dataset) return <div className="forge-inspector-empty">Slide details appear here.</div>
  const current = revisions.find((revision) => revision.id === dataset.currentArtifactRevision)
  return (
    <div className="forge-inspector" id="forge-slide-inspector">
      <header>
        <div><span>Slide inspector</span><h2>{dataset.displayName}</h2></div>
        <button
          className="forge-inspector-collapse"
          type="button"
          aria-label="Collapse slide inspector"
          title="Collapse slide inspector"
          onClick={onCollapse}
        >
          <SidebarSimple />
        </button>
      </header>
      <div className="forge-inspector-tabs" role="tablist">
        {(['export', 'annotations', 'history'] as const).map((item) => (
          <button type="button" role="tab" aria-selected={section === item} key={item} onClick={() => setSection(item)}>
            {item === 'export' ? 'Crop & export' : item === 'annotations' ? 'Annotations' : 'History'}
          </button>
        ))}
      </div>
      {section === 'export' ? (
        <ExportInspector
          dataset={dataset}
          series={series}
          current={current}
          capabilities={capabilities}
          connection={connection}
          viewerUpload={viewerUpload}
          cropDraft={cropDraft}
          cropEditing={cropEditing}
          onCropDraft={onCropDraft}
          onCropEditing={onCropEditing}
          onInspect={onInspect}
          onConfigure={onConfigure}
          onConvert={onConvert}
          onCancel={onCancel}
          onApprove={onApprove}
          onConnect={onConnect}
          onUpload={onUpload}
          onRemove={onRemove}
          onViewRevision={onViewRevision}
          onViewSource={onViewSource}
          viewingRevisionId={viewingRevisionId}
        />
      ) : null}
      {section === 'annotations' ? (
        <section className="forge-inspector-section">
          <div className="forge-section-heading"><h3>Annotations</h3><span>Source coordinates</span></div>
          <AnnotationToolbar activeTool={activeTool} onTool={onTool} />
          <div className="forge-layer-row">
            <span><i /> Layer 1</span>
            <small>{annotations.length ? `${annotations.length} saved locally` : 'Virtual until first mark'}</small>
          </div>
          {annotations.length ? (
            <div className="forge-annotation-list" aria-label="Annotation objects">
              {annotations.map((annotation, index) => (
                <article key={annotation.id}>
                  <span><strong>{annotation.label || annotation.type}</strong><small>Object {index + 1}</small></span>
                  <button type="button" onClick={() => onDeleteAnnotation(annotation.id)}>Delete</button>
                </article>
              ))}
            </div>
          ) : null}
          <p className="forge-help">Editable annotation records remain source-anchored. Crop exports transform only intersecting geometry.</p>
        </section>
      ) : null}
      {section === 'history' ? (
        <RevisionHistory
          dataset={dataset}
          revisions={revisions}
          viewingRevisionId={viewingRevisionId}
          onView={onViewRevision}
          onRename={onRenameRevision}
          onDelete={onDeleteRevision}
          onApprove={onApprove}
        />
      ) : null}
    </div>
  )
}

function RevisionHistory({
  dataset,
  revisions,
  viewingRevisionId,
  onView,
  onRename,
  onDelete,
  onApprove,
}: {
  dataset: Dataset
  revisions: ArtifactRevision[]
  viewingRevisionId: string
  onView: (revisionId: string) => void
  onRename: (revisionId: string, name: string) => Promise<void>
  onDelete: (revisionId: string) => Promise<void>
  onApprove: () => void
}) {
  const [renamingId, setRenamingId] = useState('')
  const [renameValue, setRenameValue] = useState('')
  const [deletingId, setDeletingId] = useState('')
  const [expandedId, setExpandedId] = useState('')

  return (
    <section className="forge-inspector-section forge-history" aria-label="Conversion history">
      <div className="forge-history-heading">
        <div>
          <span>Saved locally</span>
          <h3>Conversion history</h3>
        </div>
        <strong>{revisions.length}</strong>
      </div>
      <p className="forge-help">
        Each completed conversion keeps its verified OME-TIFF or Viewer package. Rename useful versions,
        compare them here, or delete versions you no longer need.
      </p>
      {revisions.length ? (
        <div className="forge-history-list">
          {revisions.map((revision, index) => {
            const directOme = revision.format === 'OME_DYNAMIC_V1'
            const filesAvailable = (directOme ? revision.omeBytes : revision.packageBytes) > 0
              && ['READY', 'APPROVED'].includes(revision.status)
            const canView = filesAvailable
            const isCurrent = revision.id === dataset.currentArtifactRevision
            const isViewing = revision.id === viewingRevisionId
            const isApproved = revision.id === dataset.approvedArtifactRevision
              || revision.status === 'APPROVED'
            const displayName = revision.name || `Conversion ${revisions.length - index}`
            const renaming = renamingId === revision.id
            const deleting = deletingId === revision.id
            const expanded = expandedId === revision.id
            return (
              <article
                className={[
                  'forge-history-card',
                  isViewing ? 'viewing' : '',
                  filesAvailable ? '' : 'unavailable',
                ].filter(Boolean).join(' ')}
                key={revision.id}
              >
                <div className="forge-history-rail" aria-hidden="true"><i /></div>
                <div className="forge-history-card-body">
                  <header>
                    <div>
                      {renaming ? (
                        <label className="forge-history-rename">
                          Conversion name
                          <input
                            value={renameValue}
                            maxLength={80}
                            autoFocus
                            onChange={(event) => setRenameValue(event.target.value)}
                            onKeyDown={(event) => {
                              if (event.key === 'Escape') setRenamingId('')
                            }}
                          />
                        </label>
                      ) : (
                        <strong>{displayName}</strong>
                      )}
                      <small>{new Date(revision.createdAt).toLocaleString()}</small>
                    </div>
                    <div className="forge-history-badges">
                      {isCurrent ? <span>Current</span> : null}
                      {isViewing ? <span>Viewing</span> : null}
                      {isApproved ? <span>Approved</span> : null}
                      {!filesAvailable ? <span>Files unavailable</span> : null}
                    </div>
                  </header>
                  {renaming ? (
                    <div className="forge-history-inline-actions">
                      <button
                        className="forge-primary"
                        type="button"
                        disabled={!renameValue.trim()}
                        onClick={() => {
                          void onRename(revision.id, renameValue.trim())
                            .then(() => setRenamingId(''))
                            .catch(() => undefined)
                        }}
                      >
                        Save name
                      </button>
                      <button type="button" onClick={() => setRenamingId('')}>Cancel</button>
                    </div>
                  ) : (
                    <>
                      <div className="forge-history-metrics">
                        <span><b>{revision.outputWidth.toLocaleString()} × {revision.outputHeight.toLocaleString()}</b> pixels</span>
                        <span>
                          <b>{filesAvailable
                            ? formatBytes(directOme ? revision.omeBytes : revision.packageBytes)
                            : 'No output'}</b>{' '}
                          {directOme ? 'direct OME-TIFF' : 'Viewer package'}
                        </span>
                        {revision.jpegQuality > 0 ? <span><b>Q{revision.jpegQuality}</b> JPEG</span> : null}
                      </div>
                      <div className="forge-history-actions">
                        <button
                          className={isViewing ? 'active' : ''}
                          type="button"
                          disabled={!canView}
                          onClick={() => onView(revision.id)}
                        >
                          {isViewing ? 'Viewing now' : 'View slide'}
                        </button>
                        {canView && !directOme ? (
                          <a href={api.artifactPackageUrl(dataset.id, revision.id)}>
                            Download
                          </a>
                        ) : null}
                        <button
                          type="button"
                          onClick={() => {
                            setRenamingId(revision.id)
                            setRenameValue(displayName)
                          }}
                        >
                          Rename
                        </button>
                        <button
                          type="button"
                          aria-expanded={expanded}
                          onClick={() => setExpandedId(expanded ? '' : revision.id)}
                        >
                          {expanded ? 'Hide details' : 'Details'}
                        </button>
                        <button
                          className="danger"
                          type="button"
                          onClick={() => setDeletingId(revision.id)}
                        >
                          Delete
                        </button>
                      </div>
                    </>
                  )}
                  {expanded ? (
                    <dl className="forge-history-details">
                      <div><dt>Status</dt><dd>{revision.status.toLowerCase()}</dd></div>
                      <div><dt>SSIM</dt><dd>{revision.minimumWindowedSsim.toFixed(4)}</dd></div>
                      <div><dt>Max ΔE00</dt><dd>{revision.maximumRoiMeanDeltaE00.toFixed(2)}</dd></div>
                      <div><dt>Edge retention</dt><dd>{(revision.minimumEdgeDetailRetention * 100).toFixed(1)}%</dd></div>
                      <div><dt>Encoder</dt><dd>{revision.encoderProfile || 'Legacy'}</dd></div>
                      <div><dt>Revision</dt><dd><code>{revision.id}</code></dd></div>
                    </dl>
                  ) : null}
                  {isCurrent && revision.status === 'READY' && !isApproved ? (
                    <button className="forge-approve" type="button" onClick={onApprove}>
                      <CheckCircle /> Approve for Viewer upload
                    </button>
                  ) : null}
                  {revision.failure ? <p className="forge-field-error" role="alert">{revision.failure}</p> : null}
                  {deleting ? (
                    <div className="forge-history-delete" role="alert">
                      <strong>Delete “{displayName}” permanently?</strong>
                      <p>The saved package and viewer tiles for this conversion will be removed. The source slide is not deleted.</p>
                      <div>
                        <button
                          className="forge-danger"
                          type="button"
                          onClick={() => {
                            void onDelete(revision.id)
                              .then(() => setDeletingId(''))
                              .catch(() => undefined)
                          }}
                        >
                          Delete conversion
                        </button>
                        <button type="button" onClick={() => setDeletingId('')}>Keep it</button>
                      </div>
                    </div>
                  ) : null}
                </div>
              </article>
            )
          })}
        </div>
      ) : (
        <div className="forge-history-empty">
          <strong>No saved conversions yet</strong>
          <span>Complete a conversion and it will appear here automatically.</span>
        </div>
      )}
    </section>
  )
}

function ExportInspector({
  dataset,
  series,
  current,
  capabilities,
  connection,
  viewerUpload,
  cropDraft,
  cropEditing,
  onCropDraft,
  onCropEditing,
  onInspect,
  onConfigure,
  onConvert,
  onCancel,
  onApprove,
  onConnect,
  onUpload,
  onRemove,
  onViewRevision,
  onViewSource,
  viewingRevisionId,
}: {
  dataset: Dataset
  series: SeriesInfo[]
  current?: ArtifactRevision
  capabilities?: Awaited<ReturnType<typeof api.capabilities>>
  connection?: ViewerConnection
  viewerUpload?: api.ViewerUpload
  cropDraft?: CropBox
  cropEditing: boolean
  onCropDraft: (box: CropBox) => void
  onCropEditing: (editing: boolean) => void
  onInspect: () => void
  onConfigure: (values: Parameters<typeof api.configure>[1]) => Promise<void>
  onConvert: () => void
  onCancel: () => void
  onApprove: () => void
  onConnect: () => void
  onUpload: () => void
  onRemove: () => void
  onViewRevision: (revisionId: string) => void
  onViewSource: () => void
  viewingRevisionId: string
}) {
  const configurationDraft = () => ({
    series: String(dataset.selectedSeries),
    downsample: String(dataset.downsample),
    x: String(cropDraft?.x ?? dataset.cropX),
    y: String(cropDraft?.y ?? dataset.cropY),
    width: String(cropDraft?.width ?? dataset.cropWidth),
    height: String(cropDraft?.height ?? dataset.cropHeight),
  })
  const [draft, setDraft] = useState(configurationDraft)
  const [seriesLoading, setSeriesLoading] = useState(false)
  const [draftEstimate, setDraftEstimate] = useState<api.OutputEstimate | null>(null)

  useEffect(() => {
    setDraft(configurationDraft())
  }, [
    dataset.id,
    dataset.selectedSeries,
    dataset.downsample,
    dataset.cropX,
    dataset.cropY,
    dataset.cropWidth,
    dataset.cropHeight,
    cropDraft?.x,
    cropDraft?.y,
    cropDraft?.width,
    cropDraft?.height,
  ])

  const selected = series.find((item) => item.index === Number(draft.series))
  const parsed = {
    series: Number(draft.series),
    downsample: Number(draft.downsample),
    x: Number(draft.x),
    y: Number(draft.y),
    width: Number(draft.width),
    height: Number(draft.height),
  }
  const draftValid = Boolean(
    selected
    && parsed.downsample > 0
    && parsed.x >= 0
    && parsed.y >= 0
    && parsed.width > 0
    && parsed.height > 0
    && parsed.x + parsed.width <= selected.width
    && parsed.y + parsed.height <= selected.height,
  )
  const projectedWidth = draftValid ? Math.max(1, Math.floor(parsed.width / parsed.downsample)) : 0
  const projectedHeight = draftValid ? Math.max(1, Math.floor(parsed.height / parsed.downsample)) : 0
  const projectedPixels = projectedWidth * projectedHeight
  const draftMatchesSaved = parsed.series === dataset.selectedSeries
    && parsed.downsample === dataset.downsample
    && parsed.x === dataset.cropX
    && parsed.y === dataset.cropY
    && parsed.width === dataset.cropWidth
    && parsed.height === dataset.cropHeight
  const savedEstimate: api.OutputEstimate = {
    outputWidth: Math.max(1, Math.floor(dataset.cropWidth / dataset.downsample)),
    outputHeight: Math.max(1, Math.floor(dataset.cropHeight / dataset.downsample)),
    fileBytes: dataset.projectedFileBytes,
    fileLowerBytes: dataset.projectedFileLowerBytes,
    fileUpperBytes: dataset.projectedFileUpperBytes,
    workspaceBytes: dataset.estimatedOutputBytes,
  }
  const liveEstimate = draftValid
    ? estimateCropOutput(
        { x: parsed.x, y: parsed.y, width: parsed.width, height: parsed.height },
        parsed.downsample,
        dataset.sourceBytes,
        dataset.format === 'OME_TIFF' || dataset.format === 'SVS',
      )
    : null
  const displayedEstimate = draftMatchesSaved ? savedEstimate : draftEstimate ?? liveEstimate
  const estimateIsLive = !draftMatchesSaved && !draftEstimate
  const sizeReferenceEstimated = current?.sizeReferenceKind === 'estimated-staging-ome'
  const sizeReferenceLabel = sizeReferenceEstimated
    ? 'estimated OME reference'
    : 'staging OME'

  useEffect(() => {
    if (!draftValid || draftMatchesSaved) {
      setDraftEstimate(null)
      return
    }
    setDraftEstimate(null)
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      void api.estimate(dataset.id, {
        downsample: parsed.downsample,
        width: parsed.width,
        height: parsed.height,
      }, controller.signal).then(setDraftEstimate).catch((reason: unknown) => {
        if (!(reason instanceof DOMException && reason.name === 'AbortError')) {
          setDraftEstimate(null)
        }
      })
    }, 120)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [
    dataset.id,
    draftMatchesSaved,
    draftValid,
    parsed.downsample,
    parsed.height,
    parsed.width,
  ])

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draftValid) {
      onCropEditing(false)
      void onConfigure(parsed)
    }
  }

  const resetCrop = () => {
    if (!selected) return
    const fullSlide = { x: 0, y: 0, width: selected.width, height: selected.height }
    onCropDraft(fullSlide)
    onCropEditing(false)
  }

  const fullSlideCrop = Boolean(
    selected
    && cropDraft
    && isFullSlideCrop(cropDraft, selected.width, selected.height),
  )
  const cropAreaPercent = selected && draftValid
    ? Math.min(100, parsed.width * parsed.height / (selected.width * selected.height) * 100)
    : 0
  const readyCurrent = current
    && ['READY', 'APPROVED'].includes(current.status)
    && (current.format === 'OME_DYNAMIC_V1' ? current.omeBytes > 0 : current.packageBytes > 0)
    ? current
    : undefined
  const directOmePlanned = true
  const deliverableArtifact = Boolean(
    readyCurrent
    && readyCurrent.id === dataset.approvedArtifactRevision
    && readyCurrent.format === 'OME_DYNAMIC_V1'
    && readyCurrent.omeProfile === 'ome-dynamic-v1'
    && readyCurrent.jpegQuality === 75
    && readyCurrent.omeBytes > 0,
  )
  const deliverableRevision = Boolean(
    connection?.connected
    && connection.conversionMode === 'OME_DYNAMIC_V1'
    && deliverableArtifact,
  )
  const workflowStep = CANCELLABLE_STATUSES.has(dataset.status)
      ? 3
      : readyCurrent
        ? dataset.approvedArtifactRevision !== readyCurrent.id
          ? 4
          : 5
        : !series.length
          ? 1
          : 2

  const updateSeries = async (value: string) => {
    const next = series.find((item) => item.index === Number(value))
    if (!next) return
    if (next.index === dataset.selectedSeries) {
      return
    }
    const nextConfiguration = {
      series: next.index,
      downsample: parsed.downsample > 0 ? parsed.downsample : 1,
      x: 0,
      y: 0,
      width: next.width,
      height: next.height,
    }
    setDraft({
      series: value,
      downsample: String(nextConfiguration.downsample),
      x: '0',
      y: '0',
      width: String(next.width),
      height: String(next.height),
    })
    onCropDraft({ x: 0, y: 0, width: next.width, height: next.height })
    onCropEditing(false)
    setSeriesLoading(true)
    try {
      await onConfigure(nextConfiguration)
    } finally {
      setSeriesLoading(false)
    }
  }

  return (
    <section className="forge-inspector-section">
      <ConversionWorkflow currentStep={workflowStep} complete={viewerUpload?.state === 'COMPLETE'}>
      <div className="forge-source-summary workflow-only-step-1">
        <span>{dataset.format === 'VSI' ? 'VSI with matched ETS' : dataset.format === 'SVS' ? 'SVS whole slide' : 'OME-TIFF'}</span>
        <strong>{formatBytes(dataset.sourceBytes)}</strong>
        <code>{dataset.sourceFingerprint ? dataset.sourceFingerprint.slice(0, 16) : 'not fingerprinted'}</code>
      </div>
      {viewingRevisionId ? (
        <a
          className="forge-primary workflow-only-step-4"
          href="#dzi-viewer"
          onClick={onViewSource}
        >
          View original slide
        </a>
      ) : null}
      {readyCurrent ? (
        <section className="forge-result-card workflow-only-step-4" aria-label="Converted slide result">
          <div>
            <span>{readyCurrent.format === 'OME_DYNAMIC_V1' ? 'Direct OME-TIFF' : 'Prepared Viewer package'}</span>
            <strong>{formatBytes(readyCurrent.format === 'OME_DYNAMIC_V1'
              ? readyCurrent.omeBytes
              : readyCurrent.packageBytes)}</strong>
            <small>
              {readyCurrent.outputWidth.toLocaleString()} × {readyCurrent.outputHeight.toLocaleString()}
              {readyCurrent.format === 'OME_DYNAMIC_V1'
                ? ` · ${readyCurrent.omeProfile || 'canonical pyramid'}`
                : ` · JPEG Q${readyCurrent.jpegQuality}`}
              {' · '}{readyCurrent.status === 'APPROVED' ? 'Approved' : 'Ready for review'}
            </small>
          </div>
          <>
            <a
              className={viewingRevisionId === readyCurrent.id ? 'forge-primary' : 'forge-download'}
              href="#dzi-viewer"
              onClick={() => onViewRevision(readyCurrent.id)}
            >
              View converted slide
            </a>
            {readyCurrent.format !== 'OME_DYNAMIC_V1' ? (
              <a
                className="forge-download"
                href={`/api/datasets/${encodeURIComponent(dataset.id)}/package`}
              >
                Download {formatBytes(readyCurrent.packageBytes)} package
              </a>
            ) : <small>{readyCurrent.status === 'APPROVED'
              ? 'Validated and approved · ready for private Viewer delivery'
              : 'Validated locally · approve to enable private Viewer delivery'}</small>}
          </>
        </section>
      ) : null}
      {!series.length ? (
        <button
          className="forge-primary workflow-only-step-1"
          type="button"
          disabled={dataset.status === 'INSPECTING'}
          onClick={onInspect}
        >
          {dataset.status === 'INSPECTING' ? 'Opening slide…' : 'Inspect image series'}
        </button>
      ) : (
        <form className="forge-export-form" onSubmit={submit}>
          <fieldset className="forge-series-picker workflow-only-step-1">
            <legend>Image series</legend>
            <div role="list" aria-label="Image series">
              {series.filter((item) => item.rgbPlane).map((item) => {
                const active = item.index === Number(draft.series)
                const name = item.name || `Series ${item.index}`
                return (
                  <button
                    key={item.index}
                    type="button"
                    className={active ? 'active' : ''}
                    aria-pressed={active}
                    aria-label={`${name}, ${item.width} by ${item.height} pixels`}
                    disabled={seriesLoading}
                    onClick={() => void updateSeries(String(item.index))}
                  >
                    <span className="forge-series-thumbnail" aria-hidden="true">
                      <img
                        src={`/api/datasets/${encodeURIComponent(dataset.id)}/series/${item.index}/thumbnail?v=${encodeURIComponent(dataset.sourceFingerprint.slice(0, 24))}`}
                        alt=""
                        loading="lazy"
                        onError={(event) => { event.currentTarget.hidden = true }}
                      />
                      <span>{item.index + 1}</span>
                    </span>
                    <span className="forge-series-copy">
                      <strong>{name}</strong>
                      <small>{item.width.toLocaleString()} × {item.height.toLocaleString()}</small>
                      <small>{item.resolutionCount} pyramid {item.resolutionCount === 1 ? 'level' : 'levels'}</small>
                    </span>
                  </button>
                )
              })}
            </div>
            {seriesLoading ? <small role="status">Opening selected series in the viewer…</small> : null}
          </fieldset>
          <div className="forge-crop-panel workflow-only-step-2">
            <div className="forge-section-heading">
              <div>
                <h3>Export area</h3>
                <span>{cropAreaPercent.toFixed(cropAreaPercent < 10 ? 1 : 0)}% of slide</span>
              </div>
              <strong>{parsed.width.toLocaleString()} × {parsed.height.toLocaleString()} px</strong>
            </div>
            <p>Draw directly on the slide, then drag inside the box to move it or use any handle to reshape it.</p>
            <div className="forge-crop-actions">
              <button
                className={cropEditing ? 'forge-primary' : ''}
                type="button"
                aria-pressed={cropEditing}
                disabled={ACTIVE_STATUSES.has(dataset.status)}
                onClick={() => onCropEditing(!cropEditing)}
              >
                {cropEditing
                  ? 'Finish crop editing'
                  : fullSlideCrop
                    ? 'Draw crop on slide'
                    : 'Edit crop on slide'}
              </button>
              {!fullSlideCrop ? (
                <button type="button" onClick={resetCrop}>Reset to full slide</button>
              ) : null}
            </div>
          </div>
          <label className="workflow-only-step-2">Downsample
            <select
              name="downsample"
              value={draft.downsample}
              onChange={(event) => setDraft((current) => ({ ...current, downsample: event.target.value }))}
            >
              {(capabilities?.downsamples || [1, 1.5, 2, 4, 8, 16, 32]).map((value) => <option value={value} key={value}>{value}×</option>)}
            </select>
          </label>
          <div className="forge-output-summary workflow-only-step-2">
            <span>Projected output</span>
            <strong>{projectedWidth.toLocaleString()} × {projectedHeight.toLocaleString()}</strong>
            {draftMatchesSaved && current?.format === 'OME_DYNAMIC_V1'
              && ['READY', 'APPROVED'].includes(current.status) && current.omeBytes > 0 ? (
              <>
                <b>Direct OME-TIFF {formatBytes(current.omeBytes)}</b>
                <small>{current.omeProfile || 'Canonical pyramid'} · validated for private Viewer upload</small>
              </>
            ) : draftMatchesSaved && current && ['READY', 'APPROVED'].includes(current.status) && current.packageBytes > 0 ? (
              <>
                <b>Compact DZI package {formatBytes(current.packageBytes)}</b>
                <small>
                  Compared with {formatBytes(current.omeBytes)} {sizeReferenceLabel} ·{' '}
                  {(current.packageBytes * 100 / current.omeBytes).toFixed(1)}% · Q{current.jpegQuality}
                </small>
                <small>
                  Quality passed · SSIM {current.minimumWindowedSsim.toFixed(4)}
                  {' · '}max ΔE00 {current.maximumRoiMeanDeltaE00.toFixed(2)}
                  {' · '}edge {(current.minimumEdgeDetailRetention * 100).toFixed(1)}%
                </small>
                {current.packageBytes > current.omeBytes * 1.25 ? (
                  <small className="forge-field-error" role="status">
                    Size warning: this quality-compliant package is{' '}
                    {(current.packageBytes / current.omeBytes).toFixed(3)}× {sizeReferenceLabel}.
                    Review it before approval; the selected crop and downsample were preserved.
                  </small>
                ) : null}
              </>
            ) : displayedEstimate ? (
              <>
                <b>
                  Estimated direct OME-TIFF ≈ {formatBytes(displayedEstimate.fileBytes)}
                  {estimateIsLive ? ' · live' : ''}
                </b>
                <small>
                  Expected range {formatBytes(displayedEstimate.fileLowerBytes)}
                  {' – '}
                  {formatBytes(displayedEstimate.fileUpperBytes)}
                </small>
              </>
            ) : (
              <b role="status">Calculating direct OME-TIFF estimate…</b>
            )}
            <small>
              Peak conversion workspace ≤ {displayedEstimate
                ? formatBytes(displayedEstimate.workspaceBytes)
                : 'calculating…'}
            </small>
          </div>
          {!draftValid ? <p className="forge-field-error workflow-only-step-2">Crop must stay inside the selected image series.</p> : null}
          {draftValid && projectedPixels > 250_000_000 ? (
            <p className="forge-help workflow-only-step-2" role="status">
              Exact-resolution export: {(projectedPixels / 1_000_000_000).toFixed(2)} billion pixels.
              This preserves the selected {parsed.downsample}× scale but cannot meet the one-minute
              target on the 8 GB / 6-core profile.
            </p>
          ) : null}
          <button className="forge-primary workflow-only-step-2" type="submit" disabled={!draftValid}>Apply crop & export settings</button>
        </form>
      )}
      <p className="forge-help workflow-only-step-3">
        {series.length
          ? `${series.length} top-level image${series.length === 1 ? '' : 's'} · ${series.reduce((total, item) => total + item.resolutionCount, 0)} flattened resolution${series.reduce((total, item) => total + item.resolutionCount, 0) === 1 ? '' : 's'}`
          : dataset.detail}
      </p>
      {series.length ? <p className="forge-help workflow-only-step-3" role="status">{dataset.detail}</p> : null}
      <div className="forge-help workflow-only-step-5" role="status">
        <strong>{connection?.connected ? 'Viewer connected' : 'Viewer not connected'}</strong>
        {' · '}
        Next conversion · Direct OME-TIFF
        {viewerUpload ? ` · ${viewerUpload.detail}` : ''}
        {['IMAGE_READY', 'SYNCING_RESULTS', 'COMPLETE'].includes(viewerUpload?.state || '')
          && viewerUpload?.viewerSlideSha256
          ? ` · SHA verified ${viewerUpload.viewerSlideSha256.slice(0, 12)}…`
          : ''}
      </div>
      {dataset.approvedArtifactRevision && connection?.connected && !deliverableRevision ? (
        <div className="forge-format-notice workflow-only-step-5" role="status">
          <strong>{readyCurrent?.format === 'OME_DYNAMIC_V1' && connection?.connected
            ? 'Viewer update required'
            : 'Update needed before Viewer delivery'}</strong>
          <span>{readyCurrent?.format === 'OME_DYNAMIC_V1' && connection?.connected
            ? 'This Viewer does not advertise the exact ome-dynamic-v1 ingest profile. Forge will not start an upload that the server must reject.'
            : 'This approved result uses an older package format. Convert once with the current direct OME-TIFF workflow; Forge will keep the older result in History.'}</span>
        </div>
      ) : null}
      {dataset.status === 'FAILED' && dataset.detail.includes('DZI_SIZE_QUALITY_CONFLICT') ? (
        <div className="forge-compact-conflict workflow-only-step-3" role="alert">
          <strong>Compact DZI could not meet the 1.25× size limit</strong>
          <p>{dataset.detail}</p>
          <p>Your current crop is preserved. Change the crop or downsample, then retry conversion.</p>
        </div>
      ) : null}
      <div className="forge-action-stack">
        {CANCELLABLE_STATUSES.has(dataset.status)
          ? <button className="workflow-only-step-3" type="button" onClick={onCancel}>Cancel conversion</button>
          : <button className="forge-primary workflow-only-step-3" type="button" disabled={!series.length} onClick={onConvert}>
              Convert to direct OME-TIFF
            </button>}
        {current?.status === 'READY'
          && (current.packageBytes > 0 || current.format === 'OME_DYNAMIC_V1')
          && dataset.approvedArtifactRevision !== current.id
          ? <button className="forge-approve workflow-only-step-4" type="button" onClick={onApprove}><CheckCircle /> Approve {current.format === 'OME_DYNAMIC_V1' ? 'direct OME' : 'compact DZI'}</button>
          : null}
        {deliverableRevision
          ? <button className="workflow-only-step-5" type="button" onClick={onUpload}>Deliver privately to Viewer</button>
          : !connection?.connected
          ? <button className="workflow-only-step-5" type="button" onClick={onConnect}>Connect Viewer</button>
          : null}
      </div>
      </ConversionWorkflow>
      {!ACTIVE_STATUSES.has(dataset.status)
        ? <button className="forge-danger forge-remove-slide" type="button" onClick={onRemove}><Trash /> Remove from library</button>
        : null}
    </section>
  )
}

function QueueDock({
  datasets,
  notice,
  isError,
  onClearError,
  onQueueReady,
}: {
  datasets: Dataset[]
  notice: string
  isError: boolean
  onClearError: () => void
  onQueueReady: () => void
}) {
  const active = datasets.filter((dataset) => ACTIVE_STATUSES.has(dataset.status))
  const convertingSlides = active.filter((dataset) => CONVERSION_STATUSES.has(dataset.status))
  const converting = convertingSlides[0]
  const queued = active.filter((dataset) => ['QUEUED', 'WAITING_RESOURCES'].includes(dataset.status))
  const ready = datasets.filter((dataset) => QUEUEABLE_STATUSES.has(dataset.status))
  const phase = converting ? conversionPhase(converting) : undefined
  const indeterminate = phase?.indeterminate === true
  return (
    <div className={`forge-queue${isError ? ' error' : ''}`} role="status" aria-live="polite">
      <span className="forge-queue-mark" />
      <strong>{active.length
        ? `${convertingSlides.length ? `Converting ${convertingSlides.length}` : 'Starting'} · ${queued.length} queued`
        : 'Queue ready'}</strong>
      <span>{notice}</span>
      {ready.length ? (
        <button type="button" onClick={onQueueReady}>Queue {ready.length} ready slide{ready.length === 1 ? '' : 's'}</button>
      ) : null}
      {converting && phase ? (
        <label className="forge-queue-progress">
          <span>
            {phase.label}
            {converting.estimatedRemainingMs
              ? ` · ~${formatDuration(converting.estimatedRemainingMs)} left`
              : ''}
          </span>
          <progress
            aria-label={`${converting.displayName} conversion progress`}
            max="100"
            value={indeterminate ? undefined : phase.percent}
          />
          <strong>{indeterminate ? 'Finalizing…' : `${phase.percent}%`}</strong>
        </label>
      ) : null}
      {isError ? <button type="button" onClick={onClearError}>Dismiss</button> : null}
    </div>
  )
}

function conversionPhase(dataset: Dataset, directOme = false): {
  step: number
  percent: number
  label: string
  indeterminate?: boolean
} {
  if (dataset.stage === 'OPTIMIZING_OME') {
    return {
      step: 1,
      percent: 75,
      label: 'Finalizing OME-TIFF pyramid',
      indeterminate: true,
    }
  }
  const direct = directOme || dataset.stage === 'DIRECT_OME'
  if (direct) {
    const unitProgress = (dataset.totalUnits || 0) > 0
      ? Math.min(1, (dataset.completedUnits || 0) / dataset.totalUnits!)
      : 0
    const measured = ({
      DIRECT_OME: { step: 1, base: 5, span: 70, label: 'Rendering canonical OME-TIFF' },
      OPTIMIZING_OME: { step: 1, base: 75, span: 5, label: 'Optimizing OME-TIFF pyramid' },
      VALIDATING_OME: { step: 2, base: 80, span: 18, label: 'Validating canonical OME-TIFF' },
      OME_VERIFIED: { step: 3, base: 100, span: 0, label: 'Direct OME-TIFF ready for review' },
    } as Record<string, { step: number; base: number; span: number; label: string }>)[dataset.stage || '']
    if (measured) {
      return {
        step: measured.step,
        percent: Math.round(measured.base + measured.span * unitProgress),
        label: measured.label,
      }
    }
    return dataset.status === 'VALIDATING'
      ? { step: 2, percent: 85, label: 'Validating canonical OME-TIFF' }
      : { step: 1, percent: 15, label: 'Rendering canonical OME-TIFF' }
  }
  const phase = ({
    CONVERTING: { step: 1, percent: 15, label: 'Rendering selected area' },
    OPTIMIZING_OME: { step: 1, percent: 42, label: 'Rendering temporary staging pyramid' },
    VALIDATING: { step: 1, percent: 60, label: 'Verifying rendered staging image' },
    GENERATING_DZI: { step: 2, percent: 72, label: 'Selecting compact JPEG quality' },
    DZI_READY: { step: 3, percent: 93, label: 'Quality passed · packaging compact DZI' },
  } as Record<string, { step: number; percent: number; label: string }>)[dataset.status]
    || { step: 1, percent: 0, label: 'Preparing conversion' }
  const unitProgress = (dataset.totalUnits || 0) > 0
    ? Math.min(1, (dataset.completedUnits || 0) / dataset.totalUnits!)
    : 0
  const measuredStage = ({
    SOURCE_VERIFIED: { step: 1, base: 5, span: 0, label: 'Source verified · preparing RGB regions' },
    REGIONS_RENDERING: { step: 1, base: 5, span: 30, label: 'Reading source regions in parallel' },
    REGIONS_VERIFIED: { step: 1, base: 35, span: 0, label: 'RGB regions verified · assembling staging image' },
    DIRECT_DZI_SOURCE_READY: { step: 1, base: 35, span: 0, label: 'Source regions ready · bypassing temporary OME' },
    DIRECT_DZI_PREPARING: { step: 1, base: 35, span: 25, label: 'Globally aligning regions for direct DZI' },
    ASSEMBLING_OME: { step: 1, base: 35, span: 0, label: 'Assembling exact slide geometry' },
    DIRECT_OME: { step: 1, base: 5, span: 50, label: 'Rendering temporary staging pyramid' },
    OPTIMIZING_OME: { step: 1, base: 45, span: 0, label: 'Rendering temporary staging pyramid' },
    VALIDATING_OME: { step: 1, base: 58, span: 7, label: 'Verifying rendered staging image' },
    OME_VERIFIED: { step: 2, base: 65, span: 0, label: 'Preparing quality samples' },
    QUALITY_OVERVIEW: { step: 2, base: 65, span: 2, label: 'Mapping representative tissue' },
    QUALITY_ROIS: { step: 2, base: 67, span: 8, label: 'Reading 64 quality regions in parallel' },
    QUALITY_CANDIDATES: { step: 2, base: 75, span: 3, label: 'Selecting the smallest quality-safe JPEG' },
    GENERATING_DZI: { step: 2, base: 78, span: 0, label: 'Starting compact DZI encoder' },
    DZI_TILES: { step: 2, base: 78, span: 12, label: 'Encoding the Deep Zoom tile pyramid' },
    DZI_VALIDATING: { step: 2, base: 90, span: 3, label: 'Checking tile geometry and integrity' },
    DZI_LEDGER_VERIFIED: { step: 2, base: 93, span: 0, label: 'Quality passed · preparing package' },
    PACKAGING: { step: 3, base: 93, span: 7, label: 'Writing the saved DZI package' },
    PACKAGE_COMMITTED: { step: 3, base: 100, span: 0, label: 'Package committed' },
  } as Record<string, { step: number; base: number; span: number; label: string }>)[dataset.stage || '']
  if (measuredStage) {
    return {
      ...phase,
      step: measuredStage.step,
      percent: Math.round(measuredStage.base + measuredStage.span * unitProgress),
      label: measuredStage.label,
    }
  }
  return phase
}

function conversionCounter(dataset: Dataset) {
  if (dataset.stage === 'OPTIMIZING_OME') return 'Final pyramid is still being written and flushed…'
  const completed = dataset.completedUnits || 0
  const total = dataset.totalUnits || 0
  if (!total) return 'Preparing measurable work…'
  if (dataset.stage === 'REGIONS_RENDERING') {
    return `${completed.toLocaleString()} MiB written · ~${total.toLocaleString()} MiB stage estimate`
  }
  const units = ({
    QUALITY_OVERVIEW: 'overview',
    QUALITY_ROIS: 'quality regions',
    QUALITY_CANDIDATES: 'encoder candidates',
    DZI_TILES: 'estimated pyramid tiles',
    DZI_VALIDATING: 'tile checks',
    PACKAGING: 'package files',
    DIRECT_DZI_PREPARING: 'aligned regions',
  } as Record<string, string>)[dataset.stage || ''] || 'work units'
  return `${completed.toLocaleString()} of ${total.toLocaleString()} ${units}`
}

function ConversionWorkflow({
  currentStep,
  complete,
  children,
}: {
  currentStep: number
  complete: boolean
  children: ReactNode
}) {
  const [openStep, setOpenStep] = useState(currentStep)
  useEffect(() => setOpenStep(currentStep), [currentStep])
  const steps = [
    ['Inspect', 'Choose the image series'],
    ['Region', 'Set crop and scale'],
    ['Convert', 'Create direct OME-TIFF'],
    ['Review', 'Open and approve result'],
    ['Deliver', 'Send privately to Viewer'],
  ]
  return (
    <section className="forge-workflow" aria-label="Conversion workflow" data-open-step={openStep}>
      <header><strong>Slide workflow</strong><span>Complete one step at a time</span></header>
      <ol>
        {steps.map(([label, detail], index) => {
          const number = index + 1
          const done = complete || number < currentStep
          const active = !complete && number === currentStep
          return (
            <li className={done ? 'complete' : active ? 'active' : ''} key={label} aria-current={active ? 'step' : undefined}>
              <i>{done ? <CheckCircle aria-hidden="true" /> : number}</i>
              <button
                type="button"
                aria-expanded={openStep === number}
                aria-controls="forge-workflow-panel"
                onClick={() => setOpenStep(number)}
              >
                <span><strong>{label}</strong><small>{detail}</small></span>
                <CaretDown aria-hidden="true" />
              </button>
            </li>
          )
        })}
      </ol>
      <div id="forge-workflow-panel" className="forge-workflow-panel">
        {children}
      </div>
    </section>
  )
}

function BatchRemoveDialog({ count, onRemove, onClose }: { count: number; onRemove: () => void; onClose: () => void }) {
  return (
    <div className="forge-dialog-backdrop">
      <section className="forge-connect-dialog" role="dialog" aria-modal="true" aria-labelledby="forge-batch-remove-title">
        <span>Local slide library</span>
        <h2 id="forge-batch-remove-title">Remove {count} slides?</h2>
        <p>The selected slides disappear from this Forge library. Original files and completed exports remain on disk.</p>
        <button className="forge-danger" type="button" onClick={onRemove}><Trash /> Remove selected slides</button>
        <button className="forge-dialog-close" type="button" onClick={onClose}>Cancel</button>
      </section>
    </div>
  )
}

function conversionRouteLabel(dataset: Dataset, directOme: boolean) {
  if (directOme) return 'Direct OME-TIFF'
  if (['REGIONS_RENDERING', 'REGIONS_VERIFIED', 'DIRECT_DZI_SOURCE_READY', 'DIRECT_DZI_PREPARING']
    .includes(dataset.stage || '')) {
    return 'Fast DZI package'
  }
  if (['ASSEMBLING_OME', 'OPTIMIZING_OME', 'VALIDATING_OME', 'OME_VERIFIED']
    .includes(dataset.stage || '')) {
    return 'Fast DZI package'
  }
  return 'Fast DZI package'
}

function datasetFormatLabel(format: Dataset['format']) {
  if (format === 'VSI') return 'VSI / ETS'
  if (format === 'SVS') return 'SVS'
  return 'OME-TIFF'
}

function formatRate(value: number, stage?: string) {
  const label = stage === 'DZI_TILES'
    ? 'tiles/s'
    : stage === 'PACKAGING'
      ? 'files/s'
      : 'units/s'
  return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${label}`
}

function formatDuration(milliseconds: number) {
  const seconds = Math.max(1, Math.round(milliseconds / 1_000))
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  return minutes ? `${minutes}m ${remainder}s` : `${seconds}s`
}

function statusLabel(status: string) {
  return ({
    READY: 'Ready',
    NEEDS_COMPANIONS: 'ETS companions missing',
    READER_REQUIRED: 'Ready to inspect',
    VERIFYING_SOURCE: 'Verifying source',
    INSPECTING: 'Inspecting',
    READY_TO_CONVERT: 'Ready to convert',
    QUEUED: 'Queued',
    WAITING_RESOURCES: 'Waiting for resources',
    CONVERTING: 'Converting locally',
    OPTIMIZING_OME: 'Finalizing OME-TIFF',
    VALIDATING: 'Verifying staging image',
    GENERATING_DZI: 'Generating compact DZI',
    DZI_READY: 'Packaging',
    PACKAGE_READY: 'Review result',
    CONVERSION_READY: 'Staging image ready',
    CANCELLED: 'Cancelled',
    LOCAL_COPY_READY: 'Managed copy ready',
    FAILED: 'Failed',
  } as Record<string, string>)[status] || status.toLowerCase().replaceAll('_', ' ')
}

function libraryProgress(dataset: Dataset) {
  if (['PACKAGE_READY', 'READY', 'APPROVED'].includes(dataset.status)) return 100
  if (['QUEUED', 'WAITING_RESOURCES', 'READY_TO_CONVERT', 'CONVERSION_READY'].includes(dataset.status)) return 0
  if (!CONVERSION_STATUSES.has(dataset.status)) return 0
  return conversionPhase(dataset, dataset.stage === 'DIRECT_OME').percent
}

function readStored<T>(key: string, fallback: T): T {
  try {
    const stored = window.localStorage.getItem(key)
    return stored ? JSON.parse(stored) as T : fallback
  } catch {
    return fallback
  }
}

function useElapsed(startedAt?: number) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    if (!startedAt) return
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [startedAt])
  if (!startedAt) return 'less than a minute'
  const elapsedSeconds = Math.max(0, Math.floor((now - startedAt) / 1_000))
  const minutes = Math.floor(elapsedSeconds / 60)
  const seconds = elapsedSeconds % 60
  return minutes ? `${minutes}m ${seconds}s` : `${seconds}s`
}

function formatBytes(bytes: number) {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function message(error: unknown) {
  return error instanceof Error ? error.message : 'Unexpected Forge error'
}

function viewerConnectionMessage(error: unknown) {
  const detail = message(error)
  const normalized = detail.toLowerCase()
  if (normalized.includes('pairing_pending') || normalized.includes('not approved yet')) {
    return 'Approval is still pending. Approve the code in Viewer, then try again.'
  }
  if (normalized.includes('expired')) {
    return 'This pairing code expired. Request a new code and approve it in Viewer.'
  }
  if (normalized.includes('revoked') || normalized.includes('401')) {
    return 'The Viewer credential was revoked. Connect this device again.'
  }
  if (normalized.includes('url must') || normalized.includes('illegal character')) {
    return 'Enter a valid HTTPS Viewer URL, or a loopback HTTP address for local testing.'
  }
  if (normalized.includes('connection refused') || normalized.includes('unavailable')) {
    return 'Viewer is unreachable. Start Viewer and confirm its web address, then retry.'
  }
  return detail
}
