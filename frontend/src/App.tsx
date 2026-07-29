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
import { SlideViewer } from './SlideViewer'

const SERVER_DESTINATIONS = ['All slides', 'Unfiled', 'Shared', 'Processing', 'Failed', 'Trash']
const ACTIVE_STATUSES = new Set(['INSPECTING', 'CONVERTING', 'VALIDATING', 'GENERATING_DZI', 'DZI_READY'])

export function App() {
  const [datasets, setDatasets] = useState<Dataset[]>([])
  const [capabilities, setCapabilities] = useState<Awaited<ReturnType<typeof api.capabilities>>>()
  const [selectedId, setSelectedId] = useState('')
  const [seriesByDataset, setSeriesByDataset] = useState<Record<string, SeriesInfo[]>>({})
  const [artifactByDataset, setArtifactByDataset] = useState<Record<string, ArtifactRevision[]>>({})
  const [navigatorOpen, setNavigatorOpen] = useState(true)
  const [inspectorOpen, setInspectorOpen] = useState(true)
  const [railExpanded, setRailExpanded] = useState(false)
  const [activeTool, setActiveTool] = useState('pan')
  const [viewer, setViewer] = useState<OpenSeadragon.Viewer | null>(null)
  const [notice, setNotice] = useState('Loading local workspace…')
  const [error, setError] = useState('')
  const [importOpen, setImportOpen] = useState(false)
  const [importPath, setImportPath] = useState('')
  const [removeTarget, setRemoveTarget] = useState<Dataset>()
  const [pairingOpen, setPairingOpen] = useState(false)
  const [viewerUrl, setViewerUrl] = useState('http://127.0.0.1:8000')
  const [pairing, setPairing] = useState<ViewerPairing>()
  const [connection, setConnection] = useState<ViewerConnection>()
  const [viewerUpload, setViewerUpload] = useState<api.ViewerUpload>()
  const [annotationsByDataset, setAnnotationsByDataset] = useState<Record<string, AnnotationRecord[]>>({})
  const navigatorButtonRef = useRef<HTMLButtonElement>(null)

  const selected = datasets.find((item) => item.id === selectedId) ?? datasets[0]
  const selectedSeries = selected ? seriesByDataset[selected.id] ?? [] : []
  const revisions = selected ? artifactByDataset[selected.id] ?? [] : []
  const currentRevision = revisions.find((revision) => revision.id === selected?.currentArtifactRevision)

  const refresh = useCallback(async () => {
    try {
      const next = await api.datasets()
      setDatasets(next)
      setSelectedId((current) => current || next[0]?.id || '')
      for (const dataset of next) {
        void api.annotations(dataset.id).then((items) => {
          setAnnotationsByDataset((current) => ({ ...current, [dataset.id]: items }))
        })
        if (dataset.selectedSeries >= 0) {
          void api.series(dataset.id).then((items) => {
            if (items.length) setSeriesByDataset((current) => ({ ...current, [dataset.id]: items }))
          })
        }
        if (dataset.currentArtifactRevision) {
          void api.artifacts(dataset.id).then((result) => {
            setArtifactByDataset((current) => ({ ...current, [dataset.id]: result.revisions }))
          })
        }
      }
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
        void api.getViewerConnection().then(setConnection).catch(() => undefined)
        void refresh()
      })
      .catch((nextError) => setError(message(nextError)))
  }, [])

  useEffect(() => {
    if (!datasets.some((item) => ACTIVE_STATUSES.has(item.status))) return
    const timer = window.setInterval(() => void refresh(), 1500)
    return () => window.clearInterval(timer)
  }, [datasets, refresh])

  const finishImport = (next: { datasets: Dataset[] }) => {
    setDatasets(next.datasets)
    setSelectedId(next.datasets.at(-1)?.id || '')
    setImportOpen(false)
    setImportPath('')
    setNotice('Dataset inventory created')
  }

  const handleNativeImport = async () => {
    try {
      const next = await api.chooseDatasets()
      finishImport(next)
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const handlePathImport = async () => {
    if (!importPath.trim()) return
    try {
      const next = await api.importDataset(importPath.trim())
      finishImport(next)
    } catch (nextError) {
      setError(message(nextError))
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
      setNotice('Conversion started with bounded tiled I/O')
    } catch (nextError) {
      setError(message(nextError))
    }
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

  const connect = () => setPairingOpen(true)

  const beginPairing = async () => {
    try {
      setError('')
      const next = await api.startViewerPairing(viewerUrl)
      setPairing(next)
      setNotice(`Approve Viewer code ${next.userCode}`)
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const completePairing = async () => {
    try {
      const next = await api.exchangeViewerPairing()
      setConnection(next)
      setPairing(undefined)
      setPairingOpen(false)
      setNotice('PathLab Viewer connected with a revocable desktop credential')
    } catch (nextError) {
      setError(message(nextError))
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

  const createLocalAnnotation = async (geometry: string) => {
    if (!selected || ['pan', 'select', 'marquee'].includes(activeTool)) return
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
  }

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
      signOutLabel="Disconnect"
    />
  )

  return (
    <>
      <ViewerCanvasShell
      rail={rail}
      railExpanded={railExpanded}
      navigatorOpen={navigatorOpen}
      inspectorOpen={inspectorOpen}
      navigator={(
        <SlideNavigator
          datasets={datasets}
          selectedId={selected?.id || ''}
          onSelect={setSelectedId}
          onImport={() => setImportOpen(true)}
          onConnect={connect}
        />
      )}
      stage={(
        <ViewerStage
          dataset={selected}
          revision={currentRevision}
          annotations={selected ? annotationsByDataset[selected.id] || [] : []}
          activeTool={activeTool}
          viewer={viewer}
          onViewer={setViewer}
          onCreateAnnotation={createLocalAnnotation}
          onInspector={() => setInspectorOpen((current) => !current)}
        />
      )}
      inspector={(
        <Inspector
          dataset={selected}
          series={selectedSeries}
          revisions={revisions}
          annotations={selected ? annotationsByDataset[selected.id] || [] : []}
          activeTool={activeTool}
          capabilities={capabilities}
          onTool={setActiveTool}
          onInspect={inspect}
          onConfigure={updateConfiguration}
          onConvert={beginConversion}
          onCancel={() => selected && void api.cancel(selected.id).then(() => refresh())}
          onApprove={approveCurrent}
          onConnect={connect}
          onUpload={uploadApproved}
          onRemove={() => selected && setRemoveTarget(selected)}
          onDeleteAnnotation={deleteLocalAnnotation}
        />
      )}
      queue={(
        <QueueDock
          datasets={datasets}
          notice={error || notice}
          isError={Boolean(error)}
          onClearError={() => setError('')}
        />
      )}
      />
      {importOpen ? (
        <ImportDialog
          path={importPath}
          onPath={setImportPath}
          onChoose={() => void handleNativeImport()}
          onImport={() => void handlePathImport()}
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
          onViewerUrl={setViewerUrl}
          onStart={() => void beginPairing()}
          onComplete={() => void completePairing()}
          onClose={() => setPairingOpen(false)}
        />
      ) : null}
    </>
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
  onImport,
  onClose,
}: {
  path: string
  onPath: (value: string) => void
  onChoose: () => void
  onImport: () => void
  onClose: () => void
}) {
  return (
    <div className="forge-dialog-backdrop">
      <section className="forge-connect-dialog" role="dialog" aria-modal="true" aria-labelledby="forge-import-title">
        <span>Local pathology dataset</span>
        <h2 id="forge-import-title">Import slide</h2>
        <p>Select one OME-TIFF or VSI file. Forge finds the matching ETS companion tree automatically.</p>
        <button className="forge-primary" type="button" onClick={onChoose}>Choose file…</button>
        <div className="forge-dialog-divider"><span>or enter its full local path</span></div>
        <label>
          Local slide path
          <input
            type="text"
            value={path}
            onChange={(event) => onPath(event.target.value)}
            placeholder="C:\path\slide.vsi"
          />
        </label>
        <button type="button" disabled={!path.trim()} onClick={onImport}>Import this path</button>
        <button className="forge-dialog-close" type="button" onClick={onClose}>Cancel</button>
      </section>
    </div>
  )
}

function ViewerPairingDialog({
  viewerUrl,
  pairing,
  onViewerUrl,
  onStart,
  onComplete,
  onClose,
}: {
  viewerUrl: string
  pairing?: ViewerPairing
  onViewerUrl: (value: string) => void
  onStart: () => void
  onComplete: () => void
  onClose: () => void
}) {
  return (
    <div className="forge-dialog-backdrop">
      <section className="forge-connect-dialog" role="dialog" aria-modal="true" aria-labelledby="forge-connect-title">
        <span>PathLab Viewer</span>
        <h2 id="forge-connect-title">Connect to Viewer</h2>
        <p>A short-lived browser approval creates a revocable Windows Credential Manager entry for this Forge device.</p>
        {!pairing ? (
          <>
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
}: {
  datasets: Dataset[]
  selectedId: string
  onSelect: (id: string) => void
  onImport: () => void
  onConnect: () => void
}) {
  return (
    <div className="forge-navigator">
      <header>
        <div><span>Local workspace</span><strong>Slide library</strong></div>
        <button type="button" onClick={onImport}><FolderOpen /> Import</button>
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
            <span>Import an OME-TIFF or select one VSI; Forge finds its matching ETS tree.</span>
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
  annotations,
  activeTool,
  viewer,
  onViewer,
  onCreateAnnotation,
  onInspector,
}: {
  dataset?: Dataset
  revision?: ArtifactRevision
  annotations: AnnotationRecord[]
  activeTool: string
  viewer: OpenSeadragon.Viewer | null
  onViewer: (viewer: OpenSeadragon.Viewer | null) => void
  onCreateAnnotation: (geometry: string) => void
  onInspector: () => void
}) {
  const previewIdentity = dataset?.configurationRevision || String(dataset?.selectedSeries ?? '')
  const tileSource = dataset && revision && ['READY', 'APPROVED'].includes(revision.status)
    ? `/api/datasets/${encodeURIComponent(dataset.id)}/derivative/slide.dzi?revision=${encodeURIComponent(revision.id)}`
    : dataset && dataset.selectedSeries >= 0 && ['READY_TO_CONVERT', 'PACKAGE_READY', 'CONVERSION_READY'].includes(dataset.status)
      ? `/api/datasets/${encodeURIComponent(dataset.id)}/preview/slide.dzi?revision=${encodeURIComponent(previewIdentity)}`
      : ''
  return (
    <section className="forge-stage" aria-label="Whole-slide viewer">
      <header className="forge-viewer-header">
        <div>
          <strong>{dataset?.displayName || 'PathLab Forge viewer'}</strong>
          <span>{dataset ? `${dataset.format === 'VSI' ? 'VSI / ETS' : 'OME-TIFF'} · ${statusLabel(dataset.status)}` : 'Choose a local slide from the panel'}</span>
        </div>
        <button type="button" aria-label="Toggle inspector" onClick={onInspector}><SidebarSimple /></button>
      </header>
      {tileSource ? (
        <SlideViewer
          tileSource={tileSource}
          activeTool={activeTool}
          annotations={annotations}
          sourceWidth={dataset?.width || 1}
          sourceHeight={dataset?.height || 1}
          cropX={revision && ['READY', 'APPROVED'].includes(revision.status) ? dataset?.cropX || 0 : 0}
          cropY={revision && ['READY', 'APPROVED'].includes(revision.status) ? dataset?.cropY || 0 : 0}
          downsample={revision && ['READY', 'APPROVED'].includes(revision.status) ? dataset?.downsample || 1 : 0}
          onCreate={onCreateAnnotation}
          onReady={onViewer}
        />
      ) : (
        <div className="forge-stage-empty">
          <span className="forge-tissue-mark"><Crosshair /></span>
          <h1>{dataset ? 'Preparing slide preview' : 'Your slides, ready at launch'}</h1>
          <p>{dataset ? 'Inspect the image series, set a crop and scale, then convert. The exact result opens here before approval or upload.' : 'Import an OME-TIFF or a VSI. The slide panel remains visible so image-series selection and conversion feel like one viewer workflow.'}</p>
        </div>
      )}
      <div className="forge-viewer-tools" aria-label="Viewer controls">
        <button type="button" aria-label="Zoom out" onClick={() => viewer?.viewport.zoomBy(.67)}><MagnifyingGlassMinus /></button>
        <button type="button" aria-label="Home" onClick={() => viewer?.viewport.goHome()}><House /></button>
        <button type="button" aria-label="Zoom in" onClick={() => viewer?.viewport.zoomBy(1.5)}><MagnifyingGlassPlus /></button>
        <button type="button" aria-label="Full screen" onClick={() => viewer?.setFullScreen(!viewer.isFullPage())}><ArrowsOut /></button>
      </div>
    </section>
  )
}

function Inspector({
  dataset,
  series,
  revisions,
  annotations,
  activeTool,
  capabilities,
  onTool,
  onInspect,
  onConfigure,
  onConvert,
  onCancel,
  onApprove,
  onConnect,
  onUpload,
  onRemove,
  onDeleteAnnotation,
}: {
  dataset?: Dataset
  series: SeriesInfo[]
  revisions: ArtifactRevision[]
  annotations: AnnotationRecord[]
  activeTool: string
  capabilities?: Awaited<ReturnType<typeof api.capabilities>>
  onTool: (tool: string) => void
  onInspect: () => void
  onConfigure: (values: Parameters<typeof api.configure>[1]) => Promise<void>
  onConvert: () => void
  onCancel: () => void
  onApprove: () => void
  onConnect: () => void
  onUpload: () => void
  onRemove: () => void
  onDeleteAnnotation: (annotationId: string) => void
}) {
  const [section, setSection] = useState<'export' | 'annotations' | 'history'>('export')
  if (!dataset) return <div className="forge-inspector-empty">Slide details appear here.</div>
  const current = revisions.find((revision) => revision.id === dataset.currentArtifactRevision)
  return (
    <div className="forge-inspector">
      <header><span>Slide inspector</span><h2>{dataset.displayName}</h2></header>
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
          onInspect={onInspect}
          onConfigure={onConfigure}
          onConvert={onConvert}
          onCancel={onCancel}
          onApprove={onApprove}
          onConnect={onConnect}
          onUpload={onUpload}
          onRemove={onRemove}
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
        <section className="forge-inspector-section">
          <div className="forge-section-heading"><h3>Artifact revisions</h3><span>{revisions.length}</span></div>
          {revisions.length ? revisions.map((revision) => (
            <article className="forge-revision" key={revision.id}>
              <strong>{revision.outputWidth.toLocaleString()} × {revision.outputHeight.toLocaleString()}</strong>
              <span>{revision.status.toLowerCase()} · {new Date(revision.createdAt).toLocaleString()}</span>
              {revision.failure ? <span role="alert">{revision.failure}</span> : null}
              <code>{revision.id.slice(0, 12)}</code>
            </article>
          )) : <p className="forge-help">No conversion artifacts yet.</p>}
        </section>
      ) : null}
    </div>
  )
}

function ExportInspector({
  dataset,
  series,
  current,
  capabilities,
  onInspect,
  onConfigure,
  onConvert,
  onCancel,
  onApprove,
  onConnect,
  onUpload,
  onRemove,
}: {
  dataset: Dataset
  series: SeriesInfo[]
  current?: ArtifactRevision
  capabilities?: Awaited<ReturnType<typeof api.capabilities>>
  onInspect: () => void
  onConfigure: (values: Parameters<typeof api.configure>[1]) => Promise<void>
  onConvert: () => void
  onCancel: () => void
  onApprove: () => void
  onConnect: () => void
  onUpload: () => void
  onRemove: () => void
}) {
  const configurationDraft = () => ({
    series: String(dataset.selectedSeries),
    downsample: String(dataset.downsample),
    x: String(dataset.cropX),
    y: String(dataset.cropY),
    width: String(dataset.cropWidth),
    height: String(dataset.cropHeight),
  })
  const [draft, setDraft] = useState(configurationDraft)
  const [seriesLoading, setSeriesLoading] = useState(false)

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
  const projectedWidth = draftValid ? Math.floor(parsed.width / parsed.downsample) : 0
  const projectedHeight = draftValid ? Math.floor(parsed.height / parsed.downsample) : 0

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (draftValid) void onConfigure(parsed)
  }

  const updateSeries = async (value: string) => {
    const next = series.find((item) => item.index === Number(value))
    if (!next) return
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
        <span>{dataset.format === 'VSI' ? 'VSI with matched ETS' : 'OME-TIFF'}</span>
        <strong>{formatBytes(dataset.sourceBytes)}</strong>
        <code>{dataset.sourceFingerprint ? dataset.sourceFingerprint.slice(0, 16) : 'not fingerprinted'}</code>
      </div>
      {!series.length ? (
        <button className="forge-primary" type="button" onClick={onInspect}>Inspect image series</button>
      ) : (
        <form className="forge-export-form" onSubmit={submit}>
          <label>Image series
            <select
              name="series"
              value={draft.series}
              disabled={seriesLoading}
              onChange={(event) => void updateSeries(event.target.value)}
            >
              {series.filter((item) => item.rgbPlane).map((item) => (
                <option key={item.index} value={item.index}>{item.name || `Series ${item.index}`} · {item.width} × {item.height}</option>
              ))}
            </select>
            {seriesLoading ? <small role="status">Loading selected series preview…</small> : null}
          </label>
          <div className="forge-crop-grid">
            {[
              ['x', 'X', dataset.cropX],
              ['y', 'Y', dataset.cropY],
              ['width', 'Width', dataset.cropWidth || selected?.width || 1],
              ['height', 'Height', dataset.cropHeight || selected?.height || 1],
            ].map(([name, label]) => (
              <label key={name}>
                {label}
                <input
                  name={String(name)}
                  type="number"
                  min="0"
                  value={draft[String(name) as 'x' | 'y' | 'width' | 'height']}
                  onChange={(event) => setDraft((current) => ({
                    ...current,
                    [String(name)]: event.target.value,
                  }))}
                />
              </label>
            ))}
          </div>
          <label>Downsample
            <select
              name="downsample"
              value={draft.downsample}
              onChange={(event) => setDraft((current) => ({ ...current, downsample: event.target.value }))}
            >
              {(capabilities?.downsamples || [1, 1.5, 2, 4, 8]).map((value) => <option value={value} key={value}>{value}×</option>)}
            </select>
          </label>
          <div className="forge-output-summary">
            <span>Projected output</span>
            <strong>{projectedWidth.toLocaleString()} × {projectedHeight.toLocaleString()}</strong>
            <small>Workspace upper bound {formatBytes(dataset.estimatedOutputBytes)}</small>
          </div>
          {!draftValid ? <p className="forge-field-error">Crop must stay inside the selected image series.</p> : null}
          <button className="forge-primary" type="submit" disabled={!draftValid}>Apply settings</button>
        </form>
      )}
      <p className="forge-help">
        {series.length
          ? `${series.length} top-level image${series.length === 1 ? '' : 's'} · ${series.reduce((total, item) => total + item.resolutionCount, 0)} flattened resolution${series.reduce((total, item) => total + item.resolutionCount, 0) === 1 ? '' : 's'}`
          : dataset.detail}
      </p>
      <div className="forge-action-stack">
        {ACTIVE_STATUSES.has(dataset.status)
          ? <button type="button" onClick={onCancel}>Cancel conversion</button>
          : <button className="forge-primary" type="button" disabled={!series.length} onClick={onConvert}>Convert current revision</button>}
        {current?.status === 'READY' && dataset.approvedArtifactRevision !== current.id
          ? <button className="forge-approve" type="button" onClick={onApprove}><CheckCircle /> Approve exact result</button>
          : null}
        {dataset.approvedArtifactRevision
          ? <button type="button" onClick={onUpload}>Upload approved revision</button>
          : null}
        {current?.status === 'READY'
          ? <a className="forge-download" href={`/api/datasets/${encodeURIComponent(dataset.id)}/package`}>Export .plslide package</a>
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
}: {
  datasets: Dataset[]
  notice: string
  isError: boolean
  onClearError: () => void
}) {
  const active = datasets.filter((dataset) => ACTIVE_STATUSES.has(dataset.status))
  return (
    <div className={`forge-queue${isError ? ' error' : ''}`} role="status" aria-live="polite">
      <span className="forge-queue-mark" />
      <strong>{active.length ? `${active.length} active` : 'Queue ready'}</strong>
      <span>{notice}</span>
      {isError ? <button type="button" onClick={onClearError}>Dismiss</button> : null}
    </div>
  )
}

function statusLabel(status: string) {
  return ({
    READY: 'Ready',
    NEEDS_COMPANIONS: 'ETS companions missing',
    READER_REQUIRED: 'Ready to inspect',
    INSPECTING: 'Inspecting',
    READY_TO_CONVERT: 'Ready to convert',
    CONVERTING: 'Converting locally',
    VALIDATING: 'Validating OME-TIFF',
    GENERATING_DZI: 'Generating viewer tiles',
    DZI_READY: 'Packaging',
    PACKAGE_READY: 'Review result',
    CONVERSION_READY: 'OME-TIFF ready',
    CANCELLED: 'Cancelled',
    LOCAL_COPY_READY: 'Managed copy ready',
    FAILED: 'Failed',
  } as Record<string, string>)[status] || status.toLowerCase().replaceAll('_', ' ')
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
