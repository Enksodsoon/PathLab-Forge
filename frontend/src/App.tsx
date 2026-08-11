import {
  AnnotationToolbar,
  PathLabProductRail,
  ViewerCanvasShell,
} from '@pathlab/viewer-ui'
import {
  ArrowsOut,
  CheckCircle,
  Crosshair,
  FolderOpen,
  House,
  MagnifyingGlassMinus,
  MagnifyingGlassPlus,
  SidebarSimple,
  Trash,
} from '@phosphor-icons/react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type OpenSeadragon from 'openseadragon'

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

const SERVER_DESTINATIONS = ['All slides', 'Unfiled', 'Shared', 'Processing', 'Failed', 'Trash']
const ACTIVE_STATUSES = new Set(['VERIFYING_SOURCE', 'INSPECTING', 'QUEUED', 'WAITING_RESOURCES', 'CONVERTING', 'OPTIMIZING_OME', 'VALIDATING', 'GENERATING_DZI', 'DZI_READY'])
const CONVERSION_STATUSES = new Set(['CONVERTING', 'OPTIMIZING_OME', 'VALIDATING', 'GENERATING_DZI', 'DZI_READY'])
const CANCELLABLE_STATUSES = new Set(['QUEUED', 'WAITING_RESOURCES', ...CONVERSION_STATUSES])
const QUEUEABLE_STATUSES = new Set(['READY', 'READY_TO_CONVERT', 'CONVERSION_READY', 'FAILED', 'CANCELLED'])
const NO_ANNOTATIONS: AnnotationRecord[] = []

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
  const [annotationsByDataset, setAnnotationsByDataset] = useState<Record<string, AnnotationRecord[]>>({})
  const [featureOpen, setFeatureOpen] = useState(false)
  const [features, setFeatures] = useState<api.FeaturePack[]>([])
  const [featureLoading, setFeatureLoading] = useState(false)
  const navigatorButtonRef = useRef<HTMLButtonElement>(null)

  const selected = datasets.find((item) => item.id === selectedId) ?? datasets[0]
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

  const queueReadySlides = async () => {
    const candidates = datasets.filter((item) => QUEUEABLE_STATUSES.has(item.status))
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
      setNotice(`Approve Viewer code ${next.userCode}`)
    } catch (nextError) {
      setError(viewerConnectionMessage(nextError))
    }
  }

  const completePairing = async () => {
    try {
      const next = await api.exchangeViewerPairing()
      setConnection(next)
      setViewerUrl(next.viewerUrl)
      setPairing(undefined)
      setPairingOpen(false)
      setNotice('PathLab Viewer connected with a revocable desktop credential')
    } catch (nextError) {
      setError(viewerConnectionMessage(nextError))
    }
  }

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
    if (viewerUpload?.state !== 'UPLOADING') return
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
    <PathLabProductRail
      productName="Forge"
      expanded={railExpanded}
      navigatorOpen={navigatorOpen}
      navigatorButtonRef={navigatorButtonRef}
      storage={storage}
      onToggleExpanded={() => setRailExpanded((current) => !current)}
      onNavigator={() => setNavigatorOpen((current) => !current)}
      onUpload={selected?.approvedArtifactRevision ? uploadApproved : () => setImportOpen(true)}
      onSecurity={connect}
      onSignOut={connect}
      uploadLabel={selected?.approvedArtifactRevision ? 'Upload' : 'Import'}
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
              onSelect={(id) => {
                setSelectedId(id)
                if (window.innerWidth <= 960) setNavigatorOpen(false)
              }}
              onImport={() => setImportOpen(true)}
              onConnect={connect}
              onCollapse={() => {
                setNavigatorOpen(false)
                window.requestAnimationFrame(() => navigatorButtonRef.current?.focus())
              }}
            />
          )}
          stage={(
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
          inspector={(
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
      <button className="forge-feature-launcher" type="button" onClick={openFeatures}>
        Feature Center
      </button>
      {selected && viewerUpload?.state === 'READY_PRIVATE' ? (
        <button
          className="forge-viewer-sync-launcher"
          type="button"
          onClick={() => void api.syncViewer(selected.id)
            .then((next) => { setViewerUpload(next); setNotice(next.detail) })
            .catch((nextError) => setError(message(nextError)))}
        >
          Sync annotations to Viewer
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
          onComplete={() => void completePairing()}
          onDisconnect={() => void disconnectViewer()}
          onClose={() => setPairingOpen(false)}
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
  onComplete,
  onDisconnect,
  onClose,
}: {
  viewerUrl: string
  pairing?: ViewerPairing
  connection?: ViewerConnection
  onViewerUrl: (value: string) => void
  onStart: () => void
  onComplete: () => void
  onDisconnect: () => void
  onClose: () => void
}) {
  const [confirmingDisconnect, setConfirmingDisconnect] = useState(false)
  const connected = Boolean(connection?.connected)
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
            <p>A short-lived browser approval creates a revocable Windows Credential Manager entry for this Forge device.</p>
            <label>
              Viewer address
              <input
                type="url"
                value={viewerUrl}
                onChange={(event) => onViewerUrl(event.target.value)}
                placeholder="https://viewer.example"
              />
            </label>
            <button className="forge-primary" type="button" onClick={onStart}>Request pairing code</button>
          </>
        ) : (
          <>
            <div className="forge-pairing-code"><span>Verification code</span><strong>{pairing.userCode}</strong></div>
            <a className="forge-primary" href={pairing.verificationUrl} target="_blank" rel="noreferrer">
              Open Viewer approval
            </a>
            <button type="button" onClick={onComplete}>I approved this device</button>
            <small>Expires {new Date(pairing.expiresAt).toLocaleTimeString()}</small>
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
  onSelect,
  onImport,
  onConnect,
  onCollapse,
}: {
  datasets: Dataset[]
  selectedId: string
  onSelect: (id: string) => void
  onImport: () => void
  onConnect: () => void
  onCollapse: () => void
}) {
  return (
    <div className="forge-navigator">
      <header>
        <div><span>Local workspace</span><strong>Slide library</strong></div>
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
        <input type="search" placeholder="Search local slides" />
      </label>
      <nav aria-label="Local slides">
        {datasets.length ? datasets.map((dataset) => (
          <button
            type="button"
            key={dataset.id}
            className={dataset.id === selectedId ? 'active' : undefined}
            onClick={() => onSelect(dataset.id)}
          >
            <span className={`forge-slide-dot status-${dataset.status.toLowerCase()}`} />
            <span><strong>{dataset.displayName}</strong><small>{statusLabel(dataset.status)}</small></span>
          </button>
        )) : (
          <div className="forge-empty-nav">
            <Crosshair aria-hidden="true" />
            <strong>No local slides</strong>
            <span>Import an SVS, OME-TIFF or VSI; Forge finds matching VSI companions.</span>
          </div>
        )}
      </nav>
      <section className="forge-server-nav" aria-label="Viewer library">
        <div><span>PathLab Viewer</span><button type="button" onClick={onConnect}>Connect</button></div>
        {SERVER_DESTINATIONS.map((destination) => (
          <button type="button" key={destination} onClick={onConnect}>{destination}</button>
        ))}
      </section>
    </div>
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
    ? api.artifactDziUrl(dataset.id, revision.id)
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
      <progress aria-label="Conversion progress" max="100" value={phase.percent} />
      <div className="forge-conversion-progress-copy">
        <span>Step {phase.step} of {stages.length}</span>
        <span>{phase.percent}%</span>
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
            const canView = filesAvailable && !directOme
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
                        {canView ? (
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
      <div className="forge-source-summary">
        <span>{dataset.format === 'VSI' ? 'VSI with matched ETS' : dataset.format === 'SVS' ? 'SVS whole slide' : 'OME-TIFF'}</span>
        <strong>{formatBytes(dataset.sourceBytes)}</strong>
        <code>{dataset.sourceFingerprint ? dataset.sourceFingerprint.slice(0, 16) : 'not fingerprinted'}</code>
      </div>
      {viewingRevisionId ? (
        <a
          className="forge-primary"
          href="#dzi-viewer"
          onClick={onViewSource}
        >
          View original slide
        </a>
      ) : null}
      {readyCurrent ? (
        <section className="forge-result-card" aria-label="Converted slide result">
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
          {readyCurrent.format !== 'OME_DYNAMIC_V1' ? (
            <>
              <a
                className={viewingRevisionId === readyCurrent.id ? 'forge-primary' : 'forge-download'}
                href="#dzi-viewer"
                onClick={() => onViewRevision(readyCurrent.id)}
              >
                View converted slide
              </a>
              <a
                className="forge-download"
                href={`/api/datasets/${encodeURIComponent(dataset.id)}/package`}
              >
                Download {formatBytes(readyCurrent.packageBytes)} package
              </a>
            </>
          ) : (
            <small>Validated locally · approve to enable private Viewer upload</small>
          )}
        </section>
      ) : null}
      {!series.length ? (
        <button
          className="forge-primary"
          type="button"
          disabled={dataset.status === 'INSPECTING'}
          onClick={onInspect}
        >
          {dataset.status === 'INSPECTING' ? 'Opening slide…' : 'Inspect image series'}
        </button>
      ) : (
        <form className="forge-export-form" onSubmit={submit}>
          <fieldset className="forge-series-picker">
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
          <div className="forge-crop-panel">
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
          <label>Downsample
            <select
              name="downsample"
              value={draft.downsample}
              onChange={(event) => setDraft((current) => ({ ...current, downsample: event.target.value }))}
            >
              {(capabilities?.downsamples || [1, 1.5, 2, 4, 8, 16, 32]).map((value) => <option value={value} key={value}>{value}×</option>)}
            </select>
          </label>
          <div className="forge-output-summary">
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
          {!draftValid ? <p className="forge-field-error">Crop must stay inside the selected image series.</p> : null}
          {draftValid && projectedPixels > 250_000_000 ? (
            <p className="forge-help" role="status">
              Exact-resolution export: {(projectedPixels / 1_000_000_000).toFixed(2)} billion pixels.
              This preserves the selected {parsed.downsample}× scale but cannot meet the one-minute
              target on the 8 GB / 6-core profile.
            </p>
          ) : null}
          <button className="forge-primary" type="submit" disabled={!draftValid}>Apply crop & export settings</button>
        </form>
      )}
      <p className="forge-help">
        {series.length
          ? `${series.length} top-level image${series.length === 1 ? '' : 's'} · ${series.reduce((total, item) => total + item.resolutionCount, 0)} flattened resolution${series.reduce((total, item) => total + item.resolutionCount, 0) === 1 ? '' : 's'}`
          : dataset.detail}
      </p>
      {series.length ? <p className="forge-help" role="status">{dataset.detail}</p> : null}
      <div className="forge-help" role="status">
        <strong>{connection?.connected ? 'Viewer connected' : 'Viewer not connected'}</strong>
        {' · '}
        Next conversion · Direct OME-TIFF
        {viewerUpload ? ` · ${viewerUpload.detail}` : ''}
        {viewerUpload?.state === 'READY_PRIVATE' && viewerUpload.viewerSlideSha256
          ? ` · SHA verified ${viewerUpload.viewerSlideSha256.slice(0, 12)}…`
          : ''}
      </div>
      {dataset.status === 'FAILED' && dataset.detail.includes('DZI_SIZE_QUALITY_CONFLICT') ? (
        <div className="forge-compact-conflict" role="alert">
          <strong>Compact DZI could not meet the 1.25× size limit</strong>
          <p>{dataset.detail}</p>
          <p>Your current crop is preserved. Change the crop or downsample, then retry conversion.</p>
        </div>
      ) : null}
      <div className="forge-action-stack">
        {CANCELLABLE_STATUSES.has(dataset.status)
          ? <button type="button" onClick={onCancel}>Cancel conversion</button>
          : <button className="forge-primary" type="button" disabled={!series.length} onClick={onConvert}>
              Convert to direct OME-TIFF
            </button>}
        {current?.status === 'READY'
          && (current.packageBytes > 0 || current.format === 'OME_DYNAMIC_V1')
          && dataset.approvedArtifactRevision !== current.id
          ? <button className="forge-approve" type="button" onClick={onApprove}><CheckCircle /> Approve {current.format === 'OME_DYNAMIC_V1' ? 'direct OME' : 'compact DZI'}</button>
          : null}
        {dataset.approvedArtifactRevision
          ? <button type="button" onClick={onUpload}>Upload to Viewer</button>
          : !connection?.connected
          ? <button type="button" onClick={onConnect}>Connect Viewer</button>
          : null}
        {!ACTIVE_STATUSES.has(dataset.status)
          ? <button className="forge-danger" type="button" onClick={onRemove}><Trash /> Remove from library</button>
          : null}
      </div>
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
          <progress aria-label={`${converting.displayName} conversion progress`} max="100" value={phase.percent} />
          <strong>{phase.percent}%</strong>
        </label>
      ) : null}
      {isError ? <button type="button" onClick={onClearError}>Dismiss</button> : null}
    </div>
  )
}

function conversionPhase(dataset: Dataset, directOme = false) {
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
    OPTIMIZING_OME: 'Rendering staging image',
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
