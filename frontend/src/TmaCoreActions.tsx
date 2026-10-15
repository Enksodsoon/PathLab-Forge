import { useEffect, useState } from 'react'
import type { AnnotationRecord } from './api'
import type { DeterministicReview, DeterministicRun } from './DeterministicTools'

type Parameters = Record<string, Array<[string, string, number, number, number, number]>>
const imageTools = ['he', 'tissue', 'qc', 'nucleus_candidates', 'stain_vector', 'normalize_preview']

export function TmaCoreActions({ run, enabledTools, toolLabels, parameters, onLoadReview, onPersist, onSubmit }: {
  run: DeterministicRun; enabledTools: string[]; toolLabels: Record<string, string>; parameters: Parameters
  onLoadReview: (id: string) => Promise<DeterministicReview>
  onPersist: (id: string, reviewRevision: number) => Promise<AnnotationRecord[]>
  onSubmit: (id: string, reviewRevision: number, coreId: string, tool: string, configuration: Record<string, number>) => Promise<unknown>
}) {
  const [review, setReview] = useState<DeterministicReview | null>(null)
  const [cores, setCores] = useState<AnnotationRecord[]>([])
  const [coreId, setCoreId] = useState('')
  const [tool, setTool] = useState('he')
  const [configuration, setConfiguration] = useState<Record<string, number>>({})
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const current = run.tool === 'tma' && run.status === 'SUCCEEDED' && !run.stale
  const load = async () => {
    setBusy(true); setMessage(''); setCores([]); setCoreId('')
    try { setReview(await onLoadReview(run.id)) }
    catch (cause) { setMessage(cause instanceof Error ? cause.message : String(cause)) }
    finally { setBusy(false) }
  }
  useEffect(() => {
    let cancelled = false
    setReview(null); setCores([]); setCoreId(''); setMessage('')
    void onLoadReview(run.id).then((value) => { if (!cancelled) setReview(value) })
      .catch((cause: unknown) => { if (!cancelled) setMessage(String(cause)) })
    return () => { cancelled = true }
  }, [run.id, onLoadReview])
  const persist = async () => {
    if (!review || review.revision < 1) return
    setBusy(true); setMessage('')
    try {
      const saved = await onPersist(run.id, review.revision)
      const present = saved.filter((item) => item.parentId === run.annotationId && item.series === run.provenance.series
        && item.z === run.provenance.z && item.t === run.provenance.t && item.viewRevision === run.provenance.viewRevision
        && review.objects.some((core) => core.id === item.id && core.geometry === item.geometry && core.properties.missing !== 'true'))
      setCores(present); setCoreId(present[0]?.id || '')
      setMessage(`${present.length} present reviewed cores saved under the grid ROI. Missing cores remain in the review.`)
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : String(cause)) }
    finally { setBusy(false) }
  }
  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!review || !cores.some((core) => core.id === coreId)) return
    setBusy(true); setMessage('')
    try {
      const effective = Object.fromEntries((parameters[tool] || []).map(([key, , fallback]) => [key, configuration[key] ?? fallback]))
      await onSubmit(run.id, review.revision, coreId, tool, effective)
      setMessage('Core analysis submitted with the exact grid run, review revision and saved plane.')
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : String(cause)) }
    finally { setBusy(false) }
  }
  return <fieldset disabled={busy || !current}><legend>Save and analyze reviewed TMA cores</legend>
    <p>Correct every position and mark missing cores in the review, then save it. Persisting creates real rectangle annotations beneath this grid ROI.</p>
    <p>A persisted grid is one reviewed snapshot. Further corrections require a new grid; existing annotation edits are preserved.</p>
    <button type="button" onClick={() => { void load() }}>Reload saved grid review</button>
    <p>Grid run {run.id} · review revision {review?.revision ?? 'loading'} · series {run.provenance.series} Z{run.provenance.z} T{run.provenance.t}</p>
    <button type="button" disabled={!review || review.revision < 1} onClick={() => { void persist() }}>Persist exact reviewed grid</button>
    <form onSubmit={(event) => { void submit(event) }}>
      <label>Saved present core <select required value={coreId} onChange={(event) => setCoreId(event.target.value)}>
        {!cores.length ? <option value="">Persist the reviewed grid first</option> : null}
        {cores.map((core) => <option key={core.id} value={core.id}>{core.label || core.id} · ROI revision {core.revision}</option>)}
      </select></label>
      <label>Core analysis tool <select value={tool} onChange={(event) => { setTool(event.target.value); setConfiguration({}) }}>
        {imageTools.map((id) => <option key={id} value={id}>{toolLabels[id] || id}</option>)}
      </select></label>
      {(parameters[tool] || []).map(([key, label, fallback, minimum, maximum, step]) => <label key={key}>{label} <input type="number" required min={minimum} max={maximum} step={step}
        value={configuration[key] ?? fallback} onChange={(event) => setConfiguration({ ...configuration, [key]: event.target.valueAsNumber })} /></label>)}
      {!enabledTools.includes(tool) ? <p>Enable the verified tool pack before analyzing this core.</p> : null}
      <button type="submit" disabled={!coreId || !review || !enabledTools.includes(tool)}>Analyze selected saved core</button>
    </form>
    {message ? <p role="status">{message}</p> : null}
  </fieldset>
}
