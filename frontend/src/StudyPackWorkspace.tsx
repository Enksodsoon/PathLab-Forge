import { ArrowLeft, CheckCircle, FileArrowUp, UploadSimple } from '@phosphor-icons/react'
import { useEffect, useMemo, useRef, useState } from 'react'

import * as api from './api'

type WorkspaceDataset = { id: string; displayName: string }
type ImportedTask = api.ImportedStudyTask
const PAGE_SIZE = 50

export function StudyPackWorkspace({ datasets, onClose }: { datasets: WorkspaceDataset[]; onClose: () => void }) {
  const [fields, setFields] = useState({ packKey: '', version: '1', title: '', courseId: '', source: '', author: '', license: '', revision: '' })
  const [associations, setAssociations] = useState<api.ViewerSlideAssociation[]>([])
  const [selectedSlideId, setSelectedSlideId] = useState('')
  const [tasks, setTasks] = useState<ImportedTask[]>([])
  const [saved, setSaved] = useState<api.StudyPackRecord>()
  const [notice, setNotice] = useState('Loading linked Viewer slides…')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [pivotApproved, setPivotApproved] = useState(false)
  const [page, setPage] = useState(0)
  const [ankiFile, setAnkiFile] = useState<File>()
  const [ankiMapping, setAnkiMapping] = useState({ promptField: '0', answerField: '1', approved: false })
  const fileInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    void api.viewerSlideAssociations().then((items) => {
      setAssociations(items); setSelectedSlideId(items[0]?.viewerSlideId || '')
      setNotice(items.length ? 'Choose linked content or import a keyed bank' : 'Upload a private slide to Viewer before authoring')
    }).catch((nextError) => setError(message(nextError)))
  }, [])
  const association = associations.find((item) => item.viewerSlideId === selectedSlideId)
  const coreMetadataReady = useMemo(() => [fields.packKey, fields.version, fields.title, fields.courseId].every(Boolean)
    && Boolean(association), [fields.packKey, fields.version, fields.title, fields.courseId, association])
  const provenanceReady = useMemo(() => tasks.every((task) => Boolean(
    (task.source || fields.source) && (task.author || fields.author)
      && (task.license || fields.license) && (task.revision || fields.revision),
  )), [tasks, fields.source, fields.author, fields.license, fields.revision])
  const saveReady = coreMetadataReady && tasks.length > 0 && provenanceReady
  const pivotReady = coreMetadataReady && Boolean(fields.author && fields.license && fields.revision)
  const preview = tasks.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const datasetName = new Map(datasets.map((dataset) => [dataset.id, dataset.displayName]))
  const invalidate = () => { setSaved(undefined); setNotice('') }
  const update = (name: keyof typeof fields, value: string) => {
    invalidate(); setFields((current) => ({ ...current, [name]: value }))
  }

  const acceptImported = (items: ImportedTask[]) => {
    validateImported(items); setTasks(items); setPage(0); setSaved(undefined); setError('')
    setNotice(`${items.length} keyed task${items.length === 1 ? '' : 's'} ready with explicit keys`)
  }
  const importFile = async (file?: File) => {
    if (!file) return
    invalidate(); setError(''); setAnkiFile(file.name.toLowerCase().endsWith('.apkg') ? file : undefined)
    try {
      const lower = file.name.toLowerCase()
      if (lower.endsWith('.apkg')) {
        if (file.size > 32 * 1024 * 1024) throw new Error('Anki package exceeds 32 MiB')
        acceptImported(await api.importAnkiPackage(file))
      } else if (lower.endsWith('.qti') || lower.endsWith('.zip')) {
        if (file.size > 8 * 1024 * 1024) throw new Error('QTI package exceeds 8 MiB')
        acceptImported(await api.importQtiPackage(file))
      } else {
        if (file.size > 2 * 1024 * 1024) throw new Error('Text import exceeds 2 MiB')
        acceptImported(parseTextImport(await file.text(), file.name))
      }
    } catch (nextError) { setError(message(nextError)); setSaved(undefined) }
  }
  const importMappedAnki = async () => {
    if (!ankiFile || !ankiMapping.approved) return
    setBusy(true); setError('')
    try {
      acceptImported(await api.importAnkiPackage(ankiFile, {
        promptField: Number(ankiMapping.promptField), answerField: Number(ankiMapping.answerField), facultyApproved: true,
      }))
    } catch (nextError) { setError(message(nextError)) } finally { setBusy(false) }
  }

  const save = async () => {
    if (!saveReady || !association) return
    setBusy(true); setError('')
    try {
      const result = await api.saveStudyPack(JSON.stringify({
        schema: 'pathlab.study-pack/1', packKey: fields.packKey, version: Number(fields.version), title: fields.title,
        courseId: fields.courseId, objectives: ['Faculty authored'],
        slides: [{ viewerSlideId: association.viewerSlideId, sha256: association.sha256,
          displayName: association.displayName, license: association.license }],
        tasks: tasks.map((task) => ({ type: 'keyed', id: task.id, slideId: association.viewerSlideId,
          prompt: task.prompt, answerKey: task.answerKey, keyApproval: task.keyOrigin,
          source: task.source || fields.source, author: task.author || fields.author,
          license: task.license || fields.license, revision: task.revision || fields.revision })),
      }))
      setSaved(result); setNotice(`Saved immutable version ${result.version}`)
    } catch (nextError) { setSaved(undefined); setError(message(nextError)) } finally { setBusy(false) }
  }

  const exportPivot = async () => {
    if (!pivotReady || !association || !pivotApproved) return
    setBusy(true); setError(''); setSaved(undefined)
    try {
      await api.approvePivotManifest(association.datasetId, fields.author)
      const result = await api.exportPivotStudyPack({ datasetId: association.datasetId,
        viewerSlideId: association.viewerSlideId, packKey: fields.packKey, version: Number(fields.version),
        title: fields.title, courseId: fields.courseId, author: fields.author,
        license: fields.license, revision: fields.revision })
      setSaved(result); setNotice(`Saved ${result.title} from its durably approved PIVOT manifest`)
    } catch (nextError) { setError(message(nextError)) } finally { setBusy(false) }
  }

  const publish = async () => {
    if (!saved) return
    const checksum = saved.checksum
    setSaved(undefined); setBusy(true); setError(''); setNotice('')
    try { const published = await api.publishStudyPack(checksum); setNotice(`Privately published as ${published.id}`) }
    catch (nextError) { setSaved(undefined); setError(message(nextError)) } finally { setBusy(false) }
  }

  return <main className="forge-study-pack" aria-label="Study Pack authoring">
    <header className="forge-pivot-topbar"><button type="button" className="forge-pivot-back" aria-label="Back to Forge" onClick={onClose}><ArrowLeft /> Back to Forge</button><div className="forge-pivot-identity"><strong>PathLab Forge</strong><span>Faculty Study Packs</span></div></header>
    <section className="forge-study-pack-card">
      <div className="forge-section-heading"><div><h1>Author a Study Pack</h1><p>Uses persisted Viewer links only. No WSI pixels are copied.</p></div></div>
      <div className="forge-study-pack-grid">
        <label>Pack key<input aria-label="Pack key" value={fields.packKey} onChange={(event) => update('packKey', event.target.value)} /></label>
        <label>Version<input aria-label="Version" type="number" min="1" value={fields.version} onChange={(event) => update('version', event.target.value)} /></label>
        <label>Title<input aria-label="Title" value={fields.title} onChange={(event) => update('title', event.target.value)} /></label>
        <label>Course ID<input aria-label="Course ID" value={fields.courseId} onChange={(event) => update('courseId', event.target.value)} /></label>
        <label>Linked Viewer slide<select aria-label="Linked Viewer slide" value={selectedSlideId} onChange={(event) => { invalidate(); setSelectedSlideId(event.target.value); setPivotApproved(false) }}><option value="">Select a private Viewer slide</option>{associations.map((item) => <option key={item.viewerSlideId} value={item.viewerSlideId}>{datasetName.get(item.datasetId) || item.displayName} · {item.viewerSlideId}</option>)}</select></label>
        <label>Source<input aria-label="Source" value={fields.source} onChange={(event) => update('source', event.target.value)} /></label>
        <label>Author<input aria-label="Author" value={fields.author} onChange={(event) => update('author', event.target.value)} /></label>
        <label>License<input aria-label="License" value={fields.license} onChange={(event) => update('license', event.target.value)} /></label>
        <label>Revision<input aria-label="Revision" value={fields.revision} onChange={(event) => update('revision', event.target.value)} /></label>
      </div>
      <div className="forge-study-pack-actions">
        <button type="button" className="forge-primary" onClick={() => fileInput.current?.click()}><FileArrowUp /> Import content</button>
        <input ref={fileInput} aria-label="Import content" className="forge-visually-hidden" type="file" accept=".xml,.csv,.apkg,.qti,.zip" onChange={(event) => void importFile(event.currentTarget.files?.[0])} />
        <button type="button" disabled={!saveReady || busy} onClick={() => void save()}><CheckCircle /> Save immutable version</button>
        <button type="button" disabled={!saved || busy} onClick={() => void publish()}><UploadSimple /> Publish privately</button>
      </div>
      <p className="forge-help">QTI packages/XML, Moodle XML, CSV, and real Anki packages are bounded before reading. Missing keys are never inferred.</p>
      {ankiFile ? <details className="forge-study-pack-mapping"><summary>Faculty mapping for unsupported Anki templates</summary><div><label>Prompt field index<input aria-label="Prompt field index" type="number" min="0" max="99" value={ankiMapping.promptField} onChange={(event) => setAnkiMapping((current) => ({ ...current, promptField: event.target.value }))} /></label><label>Answer field index<input aria-label="Answer field index" type="number" min="0" max="99" value={ankiMapping.answerField} onChange={(event) => setAnkiMapping((current) => ({ ...current, answerField: event.target.value }))} /></label><label><input type="checkbox" checked={ankiMapping.approved} onChange={(event) => setAnkiMapping((current) => ({ ...current, approved: event.target.checked }))} /> I verified these fields and approve the imported keys.</label><button type="button" disabled={!ankiMapping.approved || busy} onClick={() => void importMappedAnki()}>Import approved mapping</button></div></details> : null}
      {tasks.length ? <><ol className="forge-study-pack-tasks" aria-label="Imported task preview">{preview.map((task) => <li key={task.id}><strong>{task.prompt}</strong><span>Explicit key: {task.answerKey}</span></li>)}</ol><nav className="forge-study-pack-pages" aria-label="Task preview pages"><button type="button" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>Previous</button><span>Page {page + 1} of {Math.ceil(tasks.length / PAGE_SIZE)}</span><button type="button" disabled={(page + 1) * PAGE_SIZE >= tasks.length} onClick={() => setPage((value) => value + 1)}>Next</button></nav></> : null}
      <fieldset className="forge-study-pack-pivot"><legend>Coordinate tasks from PIVOT</legend><label><input type="checkbox" checked={pivotApproved} onChange={(event) => { invalidate(); setPivotApproved(event.target.checked) }} /> I approve the current PIVOT manifest for this linked dataset.</label><button type="button" disabled={!pivotReady || !pivotApproved || busy} onClick={() => void exportPivot()}>Approve and save PIVOT tasks</button></fieldset>
      {notice ? <p role="status" aria-live="polite">{notice}</p> : null}{error ? <p role="alert">{error}</p> : null}
    </section>
  </main>
}

function parseTextImport(text: string, fileName: string): ImportedTask[] {
  if (fileName.toLowerCase().endsWith('.csv')) return parseCsv(text)
  const document = new DOMParser().parseFromString(text, 'application/xml')
  if (document.querySelector('parsererror')) throw new Error('XML import is malformed')
  const moodle = [...document.querySelectorAll('question')]
  if (moodle.length) return moodle.map((question, index) => ({ id: `moodle-${index + 1}`,
    prompt: requiredText(question.querySelector('questiontext text')?.textContent, 'Moodle question text'),
    answerKey: requiredText(question.querySelector('answer[fraction="100"] text')?.textContent, 'Moodle correct answer'),
    source: optionalText(question, 'source'), author: optionalText(question, 'author'), license: optionalText(question, 'license'),
    revision: optionalText(question, 'revision'), keyOrigin: 'imported' }))
  return [...document.querySelectorAll('assessmentItem')].map((item, index) => ({ id: item.getAttribute('identifier') || `qti-${index + 1}`,
    prompt: requiredText(item.querySelector('itemBody')?.textContent, 'QTI item body'),
    answerKey: requiredText(item.querySelector('responseDeclaration correctResponse value')?.textContent, 'QTI correct response'),
    source: optionalText(item, 'source'), author: optionalText(item, 'author'), license: optionalText(item, 'license'),
    revision: optionalText(item, 'revision'), keyOrigin: 'imported' }))
}

function parseCsv(text: string): ImportedTask[] {
  const rows = csvRows(text); const [header, ...items] = rows
  const find = (names: string[]) => header.findIndex((value) => names.includes(value.toLowerCase()))
  const prompt = find(['prompt', 'question', 'front']); const answer = find(['answer', 'answerkey', 'key', 'back'])
  if (prompt < 0 || answer < 0) throw new Error('CSV needs prompt and answer columns')
  const source = find(['source']); const author = find(['author']); const license = find(['license']); const revision = find(['revision'])
  return items.map((item, index) => ({ id: `csv-${index + 1}`, prompt: requiredText(item[prompt], 'CSV prompt'),
    answerKey: requiredText(item[answer], 'CSV answer'), source: item[source] || '', author: item[author] || '',
    license: item[license] || '', revision: item[revision] || '', keyOrigin: 'imported' }))
}

function csvRows(text: string): string[][] { const rows: string[][] = []; let row: string[] = []; let cell = ''; let quoted = false
  for (let i = 0; i < text.length; i += 1) { const c = text[i]; if (c === '"') { if (quoted && text[i + 1] === '"') { cell += '"'; i += 1 } else quoted = !quoted }
    else if (c === ',' && !quoted) { row.push(cell.trim()); cell = '' } else if ((c === '\n' || c === '\r') && !quoted) { if (c === '\r' && text[i + 1] === '\n') i += 1; row.push(cell.trim()); if (row.some(Boolean)) rows.push(row); row = []; cell = '' } else cell += c }
  if (quoted) throw new Error('CSV has an unclosed quoted field'); row.push(cell.trim()); if (row.some(Boolean)) rows.push(row); return rows }
function validateImported(items: ImportedTask[]) { if (!items.length || items.length > 10_000) throw new Error('Import must contain 1 to 10,000 keyed tasks')
  let total = 0; for (const item of items) { for (const value of [item.id, item.prompt, item.answerKey, item.source || '', item.author || '', item.license || '', item.revision || '']) { if (value.length > 4096) throw new Error('Imported field exceeds 4,096 characters'); total += value.length } } if (total > 2 * 1024 * 1024) throw new Error('Imported task text exceeds 2 MiB') }
function optionalText(root: Element, selector: string) { return root.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() || '' }
function requiredText(value: string | undefined | null, label: string) { const normalized = value?.replace(/\s+/g, ' ').trim() || ''; if (!normalized) throw new Error(`${label} is missing; a key cannot be inferred`); return normalized }
function message(error: unknown) { return error instanceof Error ? error.message : 'Unexpected Study Pack error' }
