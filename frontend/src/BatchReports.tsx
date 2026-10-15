import { useEffect, useState } from 'react'

export interface BatchItem {
  snapshot: { id: string; displayName: string; sourcePath: string; sourceFingerprint: string; configurationRevision: string; selectedSeries: number; downsample: number; cropX: number; cropY: number; cropWidth: number; cropHeight: number }
  artifactRevisionId: string; state: string; detail: string; attempts: number
}
export interface BatchSummary { id: string; createdAt: number; format: string; items: BatchItem[] }
export interface BatchReport {
  batchId: string; createdAt: number; format: string; queuePaused: boolean
  slides: Array<{ item: BatchItem; artifact: { id: string; omeSha256: string; packageSha256: string } | null; artifactBytes: number; delivery: { state: string; nextAction: string; detail: string }; nextAction: string }>
}

export function BatchReports({ batches, onReport, onRetry, onCancel, onExport, onLoadOlder }: {
  batches: BatchSummary[]
  onReport: (id: string) => Promise<BatchReport>
  onRetry: (id: string, datasetId: string) => Promise<unknown>
  onCancel: (id: string) => Promise<unknown>
  onExport: (id: string, format: 'csv' | 'json') => void
  onLoadOlder?: () => Promise<void>
}) {
  const [selected, setSelected] = useState('')
  const [report, setReport] = useState<BatchReport>()
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const id = batches.some((batch) => batch.id === selected) ? selected : batches[0]?.id || ''
  useEffect(() => {
    let stopped = false
    setReport(undefined); setError('')
    if (id) void onReport(id).then((value) => { if (!stopped) setReport(value) }).catch((failure: unknown) => { if (!stopped) setError(String(failure)) })
    return () => { stopped = true }
  }, [id, onReport])
  async function run(action: () => Promise<unknown>) {
    setBusy(true); setError('')
    try { await action(); setReport(await onReport(id)) }
    catch (failure) { setError(String(failure)) }
    finally { setBusy(false) }
  }
  return <section aria-label="Batch reports" className="tool-card">
    <h3>Batch reports</h3>
    {!batches.length ? <p>No saved batches yet. Queue selected slides to create a batch.</p> : <>
      <label>Saved batch <select value={id} onChange={(event) => setSelected(event.target.value)} disabled={busy}>
        {batches.map((batch) => <option key={batch.id} value={batch.id}>{new Date(batch.createdAt).toLocaleString()} · {batch.items.length} slides · {batch.id.slice(0, 8)}</option>)}
      </select></label>
      <button disabled={busy} onClick={() => void run(() => onReport(id))}>Refresh batch report</button>
      {onLoadOlder && <button disabled={busy} onClick={() => void run(onLoadOlder)}>Load older batches</button>}
      <button disabled={busy || !report} onClick={() => onExport(id, 'csv')}>Export batch CSV</button>
      <button disabled={busy || !report} onClick={() => onExport(id, 'json')}>Export batch JSON</button>
      <button disabled={busy || !report?.slides.some((slide) => !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(slide.item.state))} onClick={() => void run(() => onCancel(id))}>Cancel unfinished slides</button>
      {report && <>
        <p>{report.queuePaused ? 'Queue paused. Saved settings and completed artifacts are retained.' : 'Saved settings are locked for this batch.'}</p>
        <div style={{ overflowX: 'auto' }} tabIndex={0} role="region" aria-label="Per-slide batch outcomes">
          <table><thead><tr><th scope="col">Slide and saved settings</th><th scope="col">Local outcome</th><th scope="col">Viewer delivery</th><th scope="col">Next action</th></tr></thead>
            <tbody>{report.slides.map((slide) => <tr key={slide.item.snapshot.id}>
              <th scope="row">{slide.item.snapshot.displayName}<details><summary>Saved identity and settings</summary>
                <p>Source: {slide.item.snapshot.sourcePath}</p><p>Source identity: {slide.item.snapshot.sourceFingerprint || 'Not verified'}</p>
                <p>Configuration: {slide.item.snapshot.configurationRevision || 'Not selected'}</p>
                <p>Series {slide.item.snapshot.selectedSeries}; {slide.item.snapshot.downsample}×; crop {slide.item.snapshot.cropX}, {slide.item.snapshot.cropY}, {slide.item.snapshot.cropWidth} × {slide.item.snapshot.cropHeight}</p>
                <p>Artifact: {slide.item.artifactRevisionId || 'Not admitted'}</p>
                <p>SHA-256: {slide.artifact?.omeSha256 || 'Not committed'}; {slide.artifactBytes.toLocaleString()} bytes</p>
              </details></th>
              <td>{slide.item.state}<p>{slide.item.detail}</p><p>Attempts: {slide.item.attempts}</p></td>
              <td>{slide.delivery.state}<p>{slide.delivery.detail}</p></td>
              <td>{slide.nextAction}{['FAILED', 'CANCELLED'].includes(slide.item.state) && <button disabled={busy} onClick={() => void run(() => onRetry(id, slide.item.snapshot.id))}>Retry {slide.item.snapshot.displayName}</button>}</td>
            </tr>)}</tbody>
          </table>
        </div>
      </>}
    </>}
    {busy && <p role="status">Updating batch…</p>}
    {error && <p role="alert">{error}</p>}
  </section>
}
