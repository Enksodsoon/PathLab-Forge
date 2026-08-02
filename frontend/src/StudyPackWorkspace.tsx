import { ArrowLeft, CheckCircle, FileArrowUp, UploadSimple } from '@phosphor-icons/react'
import { useMemo, useState } from 'react'

import * as api from './api'

type WorkspaceDataset = { id: string; displayName: string; viewerSlideId?: string }
type ImportedTask = api.ImportedStudyTask

export function StudyPackWorkspace({ datasets, onClose }: {
  datasets: WorkspaceDataset[]
  onClose: () => void
}) {
  const [fields, setFields] = useState({
    packKey: '', version: '1', title: '', courseId: '', viewerSlideId: datasets[0]?.viewerSlideId || '',
    source: '', author: '', license: '', revision: '',
  })
  const [tasks, setTasks] = useState<ImportedTask[]>([])
  const [saved, setSaved] = useState<api.StudyPackRecord>()
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [pivotDatasetId, setPivotDatasetId] = useState(datasets[0]?.id || '')
  const [pivotApproved, setPivotApproved] = useState(false)

  const metadataReady = useMemo(() => Object.values(fields).every(Boolean), [fields])
  const update = (name: keyof typeof fields, value: string) => setFields((current) => ({ ...current, [name]: value }))

  const importFile = async (file?: File) => {
    if (!file) return
    setError('')
    try {
      const imported = file.name.toLowerCase().endsWith('.apkg')
        ? await api.importAnkiPackage(file)
        : parseTextImport(await file.text(), file.name)
      if (!imported.length) throw new Error('No keyed tasks were found in this import')
      setTasks(imported)
      setSaved(undefined)
      setNotice(`${imported.length} keyed task${imported.length === 1 ? '' : 's'} ready with imported keys`)
    } catch (nextError) {
      setError(message(nextError))
    }
  }

  const save = async () => {
    if (!metadataReady || !tasks.length) return
    setBusy(true); setError('')
    try {
      const result = await api.saveStudyPack(JSON.stringify({
        schema: 'pathlab.study-pack/1', packKey: fields.packKey, version: Number(fields.version),
        title: fields.title, courseId: fields.courseId, objectives: ['Faculty authored'],
        slides: [{ viewerSlideId: fields.viewerSlideId }],
        tasks: tasks.map((task) => ({
          type: 'keyed', id: task.id, slideId: fields.viewerSlideId, prompt: task.prompt,
          answerKey: task.answerKey, keyApproval: task.keyOrigin,
          source: fields.source, author: fields.author, license: fields.license, revision: fields.revision,
        })),
      }))
      setSaved(result); setNotice(`Saved immutable version ${result.version}`)
    } catch (nextError) { setError(message(nextError)) } finally { setBusy(false) }
  }

  const exportPivot = async () => {
    if (!metadataReady || !pivotDatasetId || !pivotApproved) return
    setBusy(true); setError('')
    try {
      const result = await api.exportPivotStudyPack({
        datasetId: pivotDatasetId, packKey: fields.packKey, version: Number(fields.version), title: fields.title,
        courseId: fields.courseId, viewerSlideId: fields.viewerSlideId, author: fields.author,
        license: fields.license, revision: fields.revision, facultyApproved: true,
      })
      setSaved(result); setNotice(`Saved ${result.title} from its approved PIVOT manifest`)
    } catch (nextError) { setError(message(nextError)) } finally { setBusy(false) }
  }

  const publish = async () => {
    if (!saved) return
    setBusy(true); setError('')
    try {
      const published = await api.publishStudyPack(saved.checksum)
      setNotice(`Privately published as ${published.id}`)
    } catch (nextError) { setError(message(nextError)) } finally { setBusy(false) }
  }

  return (
    <main className="forge-study-pack" aria-label="Study Pack authoring">
      <header className="forge-pivot-topbar">
        <button type="button" className="forge-pivot-back" aria-label="Back to Forge" onClick={onClose}><ArrowLeft /> Back to Forge</button>
        <div className="forge-pivot-identity"><strong>PathLab Forge</strong><span>Faculty Study Packs</span></div>
      </header>
      <section className="forge-study-pack-card">
        <div className="forge-section-heading"><div><h1>Author a Study Pack</h1><p>Links Viewer slide IDs only. No WSI pixels are copied.</p></div></div>
        <div className="forge-study-pack-grid">
          <label>Pack key<input aria-label="Pack key" value={fields.packKey} onChange={(event) => update('packKey', event.target.value)} /></label>
          <label>Version<input aria-label="Version" type="number" min="1" value={fields.version} onChange={(event) => update('version', event.target.value)} /></label>
          <label>Title<input aria-label="Title" value={fields.title} onChange={(event) => update('title', event.target.value)} /></label>
          <label>Course ID<input aria-label="Course ID" value={fields.courseId} onChange={(event) => update('courseId', event.target.value)} /></label>
          <label>Viewer slide ID<input aria-label="Viewer slide ID" value={fields.viewerSlideId} onChange={(event) => update('viewerSlideId', event.target.value)} /></label>
          <label>Source<input aria-label="Source" value={fields.source} onChange={(event) => update('source', event.target.value)} /></label>
          <label>Author<input aria-label="Author" value={fields.author} onChange={(event) => update('author', event.target.value)} /></label>
          <label>License<input aria-label="License" value={fields.license} onChange={(event) => update('license', event.target.value)} /></label>
          <label>Revision<input aria-label="Revision" value={fields.revision} onChange={(event) => update('revision', event.target.value)} /></label>
        </div>
        <div className="forge-study-pack-actions">
          <label className="forge-primary"><FileArrowUp /> Import content<input aria-label="Import content" type="file" accept=".xml,.qti,.csv,.apkg" hidden onChange={(event) => void importFile(event.currentTarget.files?.[0])} /></label>
          <button type="button" disabled={!metadataReady || !tasks.length || busy} onClick={() => void save()}><CheckCircle /> Save immutable version</button>
          <button type="button" disabled={!saved || busy} onClick={() => void publish()}><UploadSimple /> Publish privately</button>
        </div>
        <p className="forge-help">QTI XML, Moodle XML, CSV, and real Anki .apkg packages are accepted. Imported keys are retained; missing keys are never inferred.</p>
        {tasks.length ? <ol className="forge-study-pack-tasks">{tasks.map((task) => <li key={task.id}><strong>{task.prompt}</strong><span>Imported key: {task.answerKey}</span></li>)}</ol> : null}
        <fieldset className="forge-study-pack-pivot"><legend>Coordinate tasks from PIVOT</legend>
          <label>Approved manifest dataset<select value={pivotDatasetId} onChange={(event) => setPivotDatasetId(event.target.value)}>{datasets.map((dataset) => <option key={dataset.id} value={dataset.id}>{dataset.displayName}</option>)}</select></label>
          <label><input type="checkbox" checked={pivotApproved} onChange={(event) => setPivotApproved(event.target.checked)} /> I approve this PIVOT manifest for this immutable pack.</label>
          <button type="button" disabled={!metadataReady || !pivotDatasetId || !pivotApproved || busy} onClick={() => void exportPivot()}>Save approved PIVOT tasks</button>
        </fieldset>
        {notice ? <p role="status">{notice}</p> : null}
        {error ? <p role="alert">{error}</p> : null}
      </section>
    </main>
  )
}

function parseTextImport(text: string, fileName: string): ImportedTask[] {
  if (text.length > 2 * 1024 * 1024) throw new Error('Text import exceeds 2 MiB')
  if (fileName.toLowerCase().endsWith('.csv')) return parseCsv(text)
  const document = new DOMParser().parseFromString(text, 'application/xml')
  if (document.querySelector('parsererror')) throw new Error('XML import is malformed')
  const moodle = [...document.querySelectorAll('question')]
  if (moodle.length) return moodle.map((question, index) => ({
    id: `moodle-${index + 1}`,
    prompt: requiredText(question.querySelector('questiontext text')?.textContent, 'Moodle question text'),
    answerKey: requiredText(question.querySelector('answer[fraction="100"] text')?.textContent, 'Moodle correct answer'),
    keyOrigin: 'imported',
  }))
  const qti = [...document.querySelectorAll('assessmentItem')]
  return qti.map((item, index) => ({
    id: item.getAttribute('identifier') || `qti-${index + 1}`,
    prompt: requiredText(item.querySelector('itemBody')?.textContent, 'QTI item body'),
    answerKey: requiredText(item.querySelector('responseDeclaration correctResponse value')?.textContent, 'QTI correct response'),
    keyOrigin: 'imported',
  }))
}

function parseCsv(text: string): ImportedTask[] {
  const rows = csvRows(text)
  const [header, ...items] = rows
  const promptIndex = header.findIndex((value) => ['prompt', 'question', 'front'].includes(value.toLowerCase()))
  const answerIndex = header.findIndex((value) => ['answer', 'answerkey', 'key', 'back'].includes(value.toLowerCase()))
  if (promptIndex < 0 || answerIndex < 0) throw new Error('CSV needs prompt and answer columns')
  return items.map((item, index) => ({ id: `csv-${index + 1}`,
    prompt: requiredText(item[promptIndex], 'CSV prompt'), answerKey: requiredText(item[answerIndex], 'CSV answer'), keyOrigin: 'imported' }))
}

function csvRows(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index]
    if (character === '"') {
      if (quoted && text[index + 1] === '"') { cell += '"'; index += 1 } else { quoted = !quoted }
    } else if (character === ',' && !quoted) { row.push(cell.trim()); cell = ''
    } else if ((character === '\n' || character === '\r') && !quoted) {
      if (character === '\r' && text[index + 1] === '\n') index += 1
      row.push(cell.trim()); if (row.some(Boolean)) rows.push(row); row = []; cell = ''
    } else { cell += character }
  }
  if (quoted) throw new Error('CSV has an unclosed quoted field')
  row.push(cell.trim()); if (row.some(Boolean)) rows.push(row)
  return rows
}

function requiredText(value: string | undefined | null, label: string) {
  const normalized = value?.replace(/\s+/g, ' ').trim() || ''
  if (!normalized) throw new Error(`${label} is missing; a key cannot be inferred`)
  return normalized
}

function message(error: unknown) { return error instanceof Error ? error.message : 'Unexpected Study Pack error' }
