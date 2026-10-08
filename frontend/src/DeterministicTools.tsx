import { useEffect, useRef, useState } from 'react'
import type { AnnotationRecord } from './api'

export interface DeterministicRun {
  id: string; datasetId: string; annotationId: string; tool: string; status: string
  createdAt: number; startedAt: number; finishedAt: number; detail: string; stale: boolean
  configuration: Record<string, number>; outputs: Record<string, unknown>
  provenance: {
    sourceFingerprint: string; annotationGeometry: string; annotationType: string; annotationRevision: number
    series: number; z: number; t: number; viewRevision: string; algorithm: string; units: string
    configurationSha256: string; secondaryInputs: Record<string, string>
  }
}
export interface DeterministicRequest {
  datasetId: string; annotationId: string; tool: string; configuration: Record<string, number>
  targetDatasetId?: string; targetAnnotationId?: string; sourceLandmarks?: string; targetLandmarks?: string
  independentSourceLandmarks?: string; independentTargetLandmarks?: string
}
export interface ReviewedObject {
  id: string; datasetId: string; parentId: string; kind: string; geometry: string; classification: string
  sourceRunId: string; properties: Record<string, string>; revision: number
}
export interface DeterministicReview { runId: string; revision: number; objects: ReviewedObject[]; stainVector: number[] }

const toolLabels: Record<string, string> = {
  he: 'H&E optical density', stain_vector: 'Estimate mean stain direction', normalize_preview: 'Normalize RGB preview',
  tma: 'Manual TMA grid', tissue: 'Tissue threshold mask', qc: 'Image quality heuristics',
  nucleus_candidates: 'Dark nucleus candidates', registration: 'Manual landmark registration',
}
const parameters: Record<string, Array<[string, string, number, number, number, number]>> = {
  he: [['hematoxylinThreshold', 'Hematoxylin threshold (OD)', .15, 0, 3, .01], ['eosinThreshold', 'Eosin threshold (OD)', .15, 0, 3, .01]],
  normalize_preview: [['targetRed', 'Target mean red', 180, 1, 255, 1], ['targetGreen', 'Target mean green', 160, 1, 255, 1], ['targetBlue', 'Target mean blue', 190, 1, 255, 1]],
  tma: [['rows', 'Rows', 3, 1, 100, 1], ['columns', 'Columns', 3, 1, 100, 1]],
  tissue: [['luminanceThreshold', 'Luminance threshold', .88, .01, .99, .01]],
  nucleus_candidates: [['darknessThreshold', 'Darkness threshold (0–255)', 80, 1, 254, 1]],
}

export function DeterministicTools({ datasetId, annotations, runs, enabledTools, selectedAnnotationId,
  onSubmit, onCancel, onRefresh, onExport, onShowObjects,
  onLoadReview, onSaveReview, datasets = [], onLoadTargetAnnotations,
}: {
  datasetId: string; annotations: AnnotationRecord[]; runs: DeterministicRun[]; enabledTools: string[]
  selectedAnnotationId?: string
  onSubmit: (request: DeterministicRequest) => Promise<unknown>
  onCancel: (id: string) => Promise<unknown>
  onRefresh: () => Promise<unknown>
  onExport: (id: string) => void
  onShowObjects?: (run: DeterministicRun) => void
  onLoadReview?: (id: string) => Promise<DeterministicReview>
  onSaveReview?: (id: string, review: DeterministicReview) => Promise<DeterministicReview>
  datasets?: Array<{ id: string; displayName: string }>
  onLoadTargetAnnotations?: (datasetId: string) => Promise<AnnotationRecord[]>
}) {
  const [tool, setTool] = useState('he')
  const [roi, setRoi] = useState(selectedAnnotationId || '')
  const [configuration, setConfiguration] = useState<Record<string, number>>({})
  const [targetDatasetId, setTargetDatasetId] = useState('')
  const [targetAnnotationId, setTargetAnnotationId] = useState('')
  const [sourceLandmarks, setSourceLandmarks] = useState('')
  const [targetLandmarks, setTargetLandmarks] = useState('')
  const [independentSourceLandmarks, setIndependentSourceLandmarks] = useState('')
  const [independentTargetLandmarks, setIndependentTargetLandmarks] = useState('')
  const [overlayOpacity, setOverlayOpacity] = useState(.5)
  const [targetAnnotations, setTargetAnnotations] = useState<AnnotationRecord[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [selectedRunId, setSelectedRunId] = useState('')
  const selectedRun = runs.find((run) => run.id === selectedRunId) || runs[0]
  const scoped = annotations.filter((annotation) => (annotation.series ?? -1) >= 0 && (annotation.z ?? -1) >= 0 && (annotation.t ?? -1) >= 0)
  const chosenRoi = scoped.find((annotation) => annotation.id === roi) || scoped.find((annotation) => annotation.id === selectedAnnotationId) || scoped[0]
  const closed = chosenRoi && ['rectangle', 'ellipse', 'polygon', 'freehand', 'brush_add', 'brush_subtract'].includes(chosenRoi.type)
  const active = runs.some((run) => ['QUEUED', 'RUNNING'].includes(run.status))
  useEffect(() => { setRoi(selectedAnnotationId || ''); setSelectedRunId('') }, [datasetId, selectedAnnotationId])
  useEffect(() => {
    let cancelled = false
    setTargetAnnotations([]); setTargetAnnotationId('')
    if (targetDatasetId && onLoadTargetAnnotations) void onLoadTargetAnnotations(targetDatasetId).then((items) => {
      if (!cancelled) setTargetAnnotations(items)
    }).catch((cause: unknown) => { if (!cancelled) setError(String(cause)) })
    return () => { cancelled = true }
  }, [targetDatasetId, onLoadTargetAnnotations])
  useEffect(() => {
    if (!active) return
    const timer = window.setTimeout(() => { void onRefresh().catch((cause: unknown) => setError(String(cause))) }, 1000)
    return () => window.clearTimeout(timer)
  }, [active, runs, onRefresh])

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!chosenRoi) return
    setError(''); setBusy(true)
    const effective = Object.fromEntries((parameters[tool] || []).map(([key, , fallback]) => [key, configuration[key] ?? fallback]))
    try {
      await onSubmit({ datasetId, annotationId: chosenRoi.id, tool, configuration: effective,
        targetDatasetId, targetAnnotationId, sourceLandmarks, targetLandmarks, independentSourceLandmarks, independentTargetLandmarks })
      await onRefresh()
    } catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)) }
    finally { setBusy(false) }
  }
  return <section aria-label="Deterministic research tools" className="forge-inspector-section">
    <h3>Local research tools</h3>
    <p>Deterministic measurements and image heuristics. Review every result; these tools make no diagnostic claims.</p>
    <form onSubmit={(event) => { void submit(event) }} style={{ display: 'grid', gap: 8 }}>
      <label>Tool <select value={tool} onChange={(event) => { setTool(event.target.value); setConfiguration({}) }}>
        {Object.entries(toolLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
      </select></label>
      <label>Source ROI <select value={chosenRoi?.id || ''} onChange={(event) => setRoi(event.target.value)}>
        {!scoped.length ? <option value="">Draw and save a scoped ROI first</option> : null}
        {scoped.map((annotation, index) => <option key={annotation.id} value={annotation.id}>{annotation.label || `${annotation.type} ${index + 1}`} · series {annotation.series} Z{annotation.z} T{annotation.t}</option>)}
      </select></label>
      {(parameters[tool] || []).map(([key, label, fallback, minimum, maximum, step]) => <label key={key}>{label} <input type="number" required min={minimum} max={maximum} step={step}
        value={configuration[key] ?? fallback} onChange={(event) => setConfiguration({ ...configuration, [key]: event.target.valueAsNumber })} /></label>)}
      {tool === 'registration' ? <fieldset><legend>Three manually matched landmark pairs</legend>
        <p>Choose a target dataset and a saved target ROI. Enter source and target coordinates in matching order. The affine result remains approximate.</p>
        <label>Target slide <select required value={targetDatasetId} onChange={(event) => setTargetDatasetId(event.target.value)}><option value="">Choose a target slide</option>
          {datasets.map((dataset) => <option key={dataset.id} value={dataset.id}>{dataset.displayName}</option>)}</select></label>
        <label>Target ROI <select required value={targetAnnotationId} onChange={(event) => setTargetAnnotationId(event.target.value)}><option value="">Choose a target ROI</option>
          {targetAnnotations.map((annotation, index) => <option key={annotation.id} value={annotation.id}>{annotation.label || `${annotation.type} ${index + 1}`} · series {annotation.series} Z{annotation.z} T{annotation.t}</option>)}</select></label>
        <label>Source landmark coordinates <input required placeholder="x,y;x,y;x,y" value={sourceLandmarks} onChange={(event) => setSourceLandmarks(event.target.value)} /></label>
        <label>Target landmark coordinates <input required placeholder="x,y;x,y;x,y" value={targetLandmarks} onChange={(event) => setTargetLandmarks(event.target.value)} /></label>
        <p>Place or correct landmarks with the slide's polyline tool, then use its saved vertices here in matching order.</p>
        <SavedLandmarks label="Use saved source fit landmarks" annotations={scoped} plane={chosenRoi} fit onChoose={setSourceLandmarks} />
        <SavedLandmarks label="Use saved target fit landmarks" annotations={targetAnnotations} plane={targetAnnotations.find((item) => item.id === targetAnnotationId)} fit onChoose={setTargetLandmarks} />
        <p>Choose closed source and target ROIs for the bounded overlay. Optional independent check points must differ from fitted landmarks. Residuals use target pixels, not physical units.</p>
        <label>Independent source check coordinates <input placeholder="x,y;x,y" value={independentSourceLandmarks} onChange={(event) => setIndependentSourceLandmarks(event.target.value)} /></label>
        <label>Independent target check coordinates <input placeholder="x,y;x,y" value={independentTargetLandmarks} onChange={(event) => setIndependentTargetLandmarks(event.target.value)} /></label>
        <SavedLandmarks label="Use saved source independent checks" annotations={scoped} plane={chosenRoi} onChoose={setIndependentSourceLandmarks} />
        <SavedLandmarks label="Use saved target independent checks" annotations={targetAnnotations} plane={targetAnnotations.find((item) => item.id === targetAnnotationId)} onChoose={setIndependentTargetLandmarks} />
      </fieldset> : null}
      {!enabledTools.includes(tool) ? <p>Install and enable the verified feature pack to run this tool. Saved results remain available.</p> : null}
      {chosenRoi && !closed ? <p>Select a closed rectangle, ellipse or path ROI.</p> : null}
      <button type="submit" disabled={busy || !chosenRoi || !closed || !enabledTools.includes(tool)}>{busy ? 'Submitting…' : 'Run locally'}</button>
    </form>
    {error ? <p role="alert">{error}</p> : null}
    <div aria-label="Saved analysis runs">
      <button type="button" onClick={() => { void onRefresh().catch((cause: unknown) => setError(String(cause))) }}>Refresh runs</button>
      {runs.map((run) => <article key={run.id}>
        <button type="button" onClick={() => setSelectedRunId(run.id)}>{toolLabels[run.tool] || run.tool} · {run.status}{run.stale ? ' · stale' : ''}</button>
        {['QUEUED', 'RUNNING'].includes(run.status) ? <button type="button" onClick={() => { void onCancel(run.id).then(onRefresh).catch((cause: unknown) => setError(String(cause))) }}>Cancel run</button> : null}
      </article>)}
    </div>
    {selectedRun ? <article aria-label="Analysis result">
      <h4>{toolLabels[selectedRun.tool] || selectedRun.tool}</h4>
      <p role="status">{selectedRun.detail}</p>
      {selectedRun.stale ? <p role="alert">The source, ROI or reader has changed. This stored result is stale; rerun before using it.</p> : null}
      <p>Series {selectedRun.provenance.series} · Z{selectedRun.provenance.z} · T{selectedRun.provenance.t} · ROI revision {selectedRun.provenance.annotationRevision}</p>
      <p>{selectedRun.provenance.algorithm} · {selectedRun.provenance.units}</p>
      {typeof selectedRun.outputs.previewDataUrl === 'string' ? <div style={{ position: 'relative', width: 'fit-content', maxWidth: '100%' }}>
        <img src={selectedRun.outputs.previewDataUrl} alt={selectedRun.tool === 'registration' ? 'Source ROI for approximate registration' : 'Derived normalization preview; original image preserved'} style={{ display: 'block', maxWidth: '100%' }} />
        {typeof selectedRun.outputs.registrationOverlayDataUrl === 'string' ? <img src={selectedRun.outputs.registrationOverlayDataUrl} alt="Transformed target ROI; transparent outside target coverage" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', opacity: overlayOpacity }} /> : null}
      </div> : null}
      {typeof selectedRun.outputs.registrationOverlayDataUrl === 'string' ? <label>Target overlay opacity <input type="range" min="0" max="1" step="0.05" value={overlayOpacity} onChange={(event) => setOverlayOpacity(event.target.valueAsNumber)} /></label> : null}
      {typeof selectedRun.outputs.maskBitsetBase64 === 'string' ? <TissueMaskPreview outputs={selectedRun.outputs} /> : null}
      <pre style={{ maxHeight: 220, overflow: 'auto', whiteSpace: 'pre-wrap' }}>{JSON.stringify({ configuration: selectedRun.configuration,
        outputs: Object.fromEntries(Object.entries(selectedRun.outputs).filter(([key]) => !['previewDataUrl', 'registrationOverlayDataUrl', 'maskBitsetBase64'].includes(key))
          .map(([key, value]) => [key, Array.isArray(value) && value.length > 20 ? { count: value.length, first20: value.slice(0, 20) } : value])) }, null, 2)}</pre>
      <button type="button" onClick={() => onExport(selectedRun.id)}>Export result and provenance</button>
      {onShowObjects && selectedRun.status === 'SUCCEEDED' && !selectedRun.stale && (selectedRun.outputs.objects || selectedRun.outputs.cores) ? <button type="button" onClick={() => onShowObjects(selectedRun)}>Inspect objects in viewer</button> : null}
      {onLoadReview && onSaveReview && selectedRun.status === 'SUCCEEDED' && ['tma', 'nucleus_candidates', 'stain_vector'].includes(selectedRun.tool)
        ? <ReviewEditor key={selectedRun.id} run={selectedRun} onLoad={onLoadReview} onSave={onSaveReview} /> : null}
    </article> : <p>No saved analysis runs for this dataset.</p>}
  </section>
}

function SavedLandmarks({ label, annotations, plane, fit, onChoose }: { label: string; annotations: AnnotationRecord[]; plane?: AnnotationRecord; fit?: boolean; onChoose: (geometry: string) => void }) {
  const choices = annotations.filter((item) => plane && item.series === plane.series && item.z === plane.z && item.t === plane.t && item.viewRevision === plane.viewRevision
    && ['point', 'polyline'].includes(item.type) && (fit ? item.geometry.split(';').length === 3 : item.geometry.split(';').length <= 32))
  return <label>{label} <select value="" onChange={(event) => { const selected = choices.find((item) => item.id === event.target.value); if (selected) onChoose(selected.geometry) }}>
    <option value="">Choose saved points from the exact plane</option>
    {choices.map((item) => <option key={item.id} value={item.id}>{item.label || item.type} · {item.geometry.split(';').length} points</option>)}
  </select></label>
}

function ReviewEditor({ run, onLoad, onSave }: { run: DeterministicRun; onLoad: (id: string) => Promise<DeterministicReview>; onSave: (id: string, review: DeterministicReview) => Promise<DeterministicReview> }) {
  const [review, setReview] = useState<DeterministicReview | null>(null)
  const [index, setIndex] = useState(0)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  useEffect(() => {
    let cancelled = false
    void onLoad(run.id).then((value) => { if (!cancelled) setReview(value) }).catch((cause: unknown) => { if (!cancelled) setMessage(String(cause)) })
    return () => { cancelled = true }
  }, [run.id, onLoad])
  const object = review?.objects[index]
  const updateObject = (changes: Partial<ReviewedObject>) => {
    if (!review || !object) return
    setReview({ ...review, objects: review.objects.map((value, at) => at === index ? { ...value, ...changes } : value) })
  }
  const save = async () => {
    if (!review) return
    setBusy(true); setMessage('')
    try { setReview(await onSave(run.id, review)); setMessage('Reviewed changes saved. Original run output remains available in export.') }
    catch (cause) { setMessage(cause instanceof Error ? cause.message : String(cause)) }
    finally { setBusy(false) }
  }
  if (!review) return <p role="status">{message || 'Loading saved review…'}</p>
  return <fieldset disabled={busy || run.stale}><legend>Review and correct results</legend>
    {review.stainVector.length ? <div><p>Manually correct the single mean OD direction. Saving normalizes it to unit length.</p>
      {review.stainVector.map((value, channel) => <label key={channel}>{['Red OD', 'Green OD', 'Blue OD'][channel]} <input type="number" min="0" step="0.001" required value={value}
        onChange={(event) => setReview({ ...review, stainVector: review.stainVector.map((original, at) => at === channel ? event.target.valueAsNumber : original) })} /></label>)}
    </div> : null}
    {object ? <div>
      <label>Object number ({review.objects.length} saved objects) <input type="number" min="1" max={review.objects.length} step="1" value={index + 1} onChange={(event) => {
        const next = event.target.valueAsNumber - 1
        if (Number.isInteger(next) && next >= 0 && next < review.objects.length) setIndex(next)
      }} /></label>
      <label>Label <input value={object.classification} maxLength={120} onChange={(event) => updateObject({ classification: event.target.value })} /></label>
      <label>Source coordinates <input value={object.geometry} onChange={(event) => updateObject({ geometry: event.target.value })} /></label>
      {object.kind === 'TMA_CORE' ? <label><input type="checkbox" checked={object.properties.missing === 'true'} onChange={(event) => updateObject({ properties: { ...object.properties, missing: String(event.target.checked) } })} />Core missing</label>
        : <div><button type="button" onClick={() => updateObject({ properties: { ...object.properties, accepted: 'true', reviewRequired: 'false' } })}>Accept candidate</button>
          <button type="button" onClick={() => updateObject({ properties: { ...object.properties, accepted: 'false', reviewRequired: 'false' } })}>Reject candidate</button>
          <p>{object.properties.accepted === 'true' ? 'Accepted' : object.properties.accepted === 'false' ? 'Rejected' : 'Awaiting review'}</p></div>}
    </div> : null}
    {!review.objects.length && !review.stainVector.length ? <p>No editable objects in this result.</p> : <button type="button" onClick={() => { void save() }}>Save reviewed changes</button>}
    {message ? <p role="status">{message}</p> : null}
  </fieldset>
}

function TissueMaskPreview({ outputs }: { outputs: Record<string, unknown> }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const width = Number(outputs.maskWidth), height = Number(outputs.maskHeight)
    if (!canvas.current || !Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || width * height > 4_194_304) return
    const data = atob(String(outputs.maskBitsetBase64))
    const scale = Math.max(1, Math.max(width, height) / 512)
    canvas.current.width = Math.max(1, Math.floor(width / scale)); canvas.current.height = Math.max(1, Math.floor(height / scale))
    const context = canvas.current.getContext('2d')
    if (!context) return
    const image = context.createImageData(canvas.current.width, canvas.current.height)
    for (let y = 0; y < canvas.current.height; y++) for (let x = 0; x < canvas.current.width; x++) {
      const bit = Math.floor(y * scale) * width + Math.floor(x * scale)
      const included = (data.charCodeAt(Math.floor(bit / 8)) & (1 << (bit % 8))) !== 0
      const index = (y * canvas.current.width + x) * 4
      image.data[index] = included ? 60 : 245; image.data[index + 1] = included ? 140 : 245; image.data[index + 2] = included ? 110 : 245; image.data[index + 3] = 255
    }
    context.putImageData(image, 0, 0)
  }, [outputs])
  return <figure><canvas ref={canvas} role="img" aria-label="Actual threshold tissue mask in source ROI" style={{ maxWidth: '100%' }} /><figcaption>Actual threshold mask · source origin {String(outputs.maskX)}, {String(outputs.maskY)}. Green pixels pass the configured threshold.</figcaption></figure>
}
