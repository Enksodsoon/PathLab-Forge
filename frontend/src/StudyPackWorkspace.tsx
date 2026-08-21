import { ArrowLeft, ArrowRight, Brain, CheckCircle, FileArrowUp, Plus, UploadSimple } from '@phosphor-icons/react'
import { useEffect, useMemo, useRef, useState } from 'react'

import * as api from './api'

const ACTIONS = [
  ['continue', 'Continue practice', 'เรียนต่อ', 'CONTINUE_PRACTICE'],
  ['offer_hint', 'Open a faculty hint', 'เปิดคำใบ้ของอาจารย์', 'HINT_SUPPORT'],
  ['ask_confidence', 'Check confidence', 'ตรวจสอบความมั่นใจ', 'CHECK_CONFIDENCE'],
  ['ask_source_check', 'Verify the source', 'ตรวจสอบแหล่งข้อมูล', 'VERIFY_SOURCE'],
  ['retrieve', 'Review a previous task', 'ทบทวนคำถามก่อนหน้า', 'REVIEW_PREVIOUS'],
  ['pause', 'Take a short break', 'พักสั้น ๆ', 'TAKE_BREAK'],
] as const

type Task = api.StudyPackDefinition['tasks'][number]

const emptyTask: Task = {
  id: '', type: 'multiple-choice', slideId: '', prompt: '', options: ['', ''], answerKey: '',
  hints: [], explanation: '', sources: [{ title: '', url: 'https://' }],
}

export function StudyPackWorkspace({ onClose }: { onClose: () => void }) {
  const [connection, setConnection] = useState<api.ViewerConnection>()
  const [slides, setSlides] = useState<api.ViewerRemoteItem[]>([])
  const [savedPacks, setSavedPacks] = useState<api.StudyPackRecord[]>([])
  const [fields, setFields] = useState({
    packKey: '', version: 1, title: '', author: '', license: '', provenance: '', revision: '',
  })
  const [tasks, setTasks] = useState<Task[]>([])
  const [draft, setDraft] = useState<Task>(emptyTask)
  const [preview, setPreview] = useState<{ checksum: string; canonicalCore: api.StudyPackDefinition }>()
  const [previewIndex, setPreviewIndex] = useState(0)
  const [visited, setVisited] = useState<Set<number>>(new Set())
  const [keyboardChecked, setKeyboardChecked] = useState(false)
  const [saved, setSaved] = useState<api.StudyPackRecord>()
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('Loading paired Viewer slides…')
  const [error, setError] = useState('')
  const fileInput = useRef<HTMLInputElement>(null)
  const previewHeading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    void Promise.all([api.getViewerConnection(), api.studyPacks()]).then(async ([nextConnection, packs]) => {
      setConnection(nextConnection); setSavedPacks(packs)
      if (!nextConnection.connected) { setNotice('Connect Forge to Viewer before authoring.'); return }
      const library = await api.syncViewerLibrary()
      setSlides(library.items.filter((item) => item.state === 'ready_private' || item.state === 'published'))
      setDraft((current) => ({ ...current, slideId: library.items[0]?.id ?? '' }))
      setNotice(library.items.length ? 'Choose an accepted Viewer slide and author tasks.' : 'No ready Viewer slides are available.')
    }).catch((caught) => setError(message(caught)))
  }, [])

  useEffect(() => {
    if (!preview) return
    setVisited((current) => new Set(current).add(previewIndex))
    previewHeading.current?.focus()
  }, [preview, previewIndex])

  const definition = useMemo<api.StudyPackDefinition>(() => ({
    schema: 'pathlab.study-pack/1', packKey: fields.packKey.trim(), version: fields.version,
    title: fields.title.trim(), author: fields.author.trim(), license: fields.license.trim(),
    provenance: fields.provenance.trim(), revision: fields.revision.trim(), languages: ['en', 'th'],
    slides: [...new Set(tasks.map((task) => task.slideId))].map((id) => {
      const slide = slides.find((item) => item.id === id)
      return { viewerSlideId: id, sha256: slide?.contentSha256 ?? '', displayName: slide?.displayName ?? '' }
    }), tasks,
  }), [fields, slides, tasks])

  const updateField = (name: keyof typeof fields, value: string | number) => {
    setPreview(undefined); setSaved(undefined); setFields((current) => ({ ...current, [name]: value }))
  }

  const addTask = () => {
    const options = draft.type === 'multiple-choice' ? draft.options?.map((item) => item.trim()).filter(Boolean) : undefined
    const next = { ...draft, id: draft.id.trim(), prompt: draft.prompt.trim(), options,
      answerKey: draft.type === 'multiple-choice' ? draft.answerKey?.trim() : undefined,
      hints: draft.hints.map((item) => item.trim()).filter(Boolean).slice(0, 3),
      explanation: draft.explanation.trim(), sources: draft.sources.map((item) => ({ title: item.title.trim(), url: item.url.trim() })),
    }
    setTasks((current) => [...current.filter((item) => item.id !== next.id), next])
    setDraft({ ...emptyTask, slideId: draft.slideId, id: `task-${tasks.length + 2}` })
    setPreview(undefined); setSaved(undefined); setNotice('Task added. Preview is required after every edit.')
  }

  const importCsv = async (file?: File) => {
    if (!file) return
    if (file.size > 2 * 1024 * 1024) { setError('CSV exceeds 2 MiB.'); return }
    try {
      const rows = csvRows(await file.text())
      const [header, ...values] = rows
      const index = (name: string) => header.indexOf(name)
      for (const required of ['id', 'type', 'slideId', 'prompt', 'explanation', 'sourceTitle', 'sourceUrl']) {
        if (index(required) < 0) throw new Error(`CSV column ${required} is required.`)
      }
      const imported = values.map((row): Task => {
        const type = row[index('type')] as Task['type']
        const options = (row[index('options')] ?? '').split('|').map((item) => item.trim()).filter(Boolean)
        return {
          id: row[index('id')], type, slideId: row[index('slideId')], prompt: row[index('prompt')],
          ...(type === 'multiple-choice' ? { options, answerKey: row[index('answerKey')] } : {
            targetX: Number(row[index('targetX')]), targetY: Number(row[index('targetY')]),
            targetWidth: Number(row[index('targetWidth')]), targetHeight: Number(row[index('targetHeight')]),
            tolerance: Number(row[index('tolerance')]),
          }),
          hints: [row[index('hint1')], row[index('hint2')], row[index('hint3')]].filter(Boolean),
          explanation: row[index('explanation')],
          sources: [{ title: row[index('sourceTitle')], url: row[index('sourceUrl')] }],
        }
      })
      if (!imported.length || imported.length + tasks.length > 500) throw new Error('CSV must keep the pack between 1 and 500 tasks.')
      setTasks((current) => [...current, ...imported]); setPreview(undefined); setSaved(undefined)
      setNotice(`${imported.length} CSV tasks imported with explicit answers.`); setError('')
    } catch (caught) { setError(message(caught)) }
  }

  const startPreview = async () => {
    setBusy(true); setError('')
    try {
      const result = await api.previewStudyPack(definition)
      setPreview(result); setPreviewIndex(0); setVisited(new Set()); setKeyboardChecked(false); setSaved(undefined)
      setNotice('Inspect every task, all faculty feedback, and all bilingual action cards.')
    } catch (caught) { setError(message(caught)) }
    finally { setBusy(false) }
  }

  const attest = async () => {
    if (!preview || visited.size !== preview.canonicalCore.tasks.length || !keyboardChecked) return
    setBusy(true); setError('')
    try {
      const result = await api.saveStudyPack({
        ...preview.canonicalCore, checksum: preview.checksum,
        facultyPreview: { packChecksum: preview.checksum, previewVersion: 'pathlab.study-preview/1', reviewedAt: new Date().toISOString() },
      })
      setSaved(result); setSavedPacks((current) => [result, ...current.filter((item) => item.checksum !== result.checksum)])
      setNotice(`Immutable Study Pack v${result.version} saved after faculty preview.`)
    } catch (caught) { setError(message(caught)) }
    finally { setBusy(false) }
  }

  const publish = async (pack: api.StudyPackRecord) => {
    setBusy(true); setError('')
    try { await api.publishStudyPack(pack.checksum); setNotice('Published privately to Viewer after capability recheck.') }
    catch (caught) { setError(message(caught)) }
    finally { setBusy(false) }
  }

  const previewTask = preview?.canonicalCore.tasks[previewIndex]
  return <main className="forge-study-workspace">
    <header><button type="button" onClick={onClose}><ArrowLeft aria-hidden="true" /> Back to Forge</button><div><strong>PathLab Forge</strong><span>Faculty Study Packs</span></div></header>
    <div className="forge-study-layout">
      <section className="forge-study-author" aria-labelledby="study-author-title">
        <span className="forge-study-eyebrow">Educational beta</span><h1 id="study-author-title">Author a Study Pack</h1>
        <p>Only immutable references to privacy-passed Viewer slides are published. Pixels remain in Viewer.</p>
        {!connection?.connected ? <p role="alert">Connect Viewer from the Forge navigation before continuing.</p> : null}
        <div className="forge-study-metadata">
          <label>Pack key<input value={fields.packKey} onChange={(event) => updateField('packKey', event.target.value)} /></label>
          <label>Version<input type="number" min="1" value={fields.version} onChange={(event) => updateField('version', Number(event.target.value))} /></label>
          <label>Title<input value={fields.title} onChange={(event) => updateField('title', event.target.value)} /></label>
          <label>Author<input value={fields.author} onChange={(event) => updateField('author', event.target.value)} /></label>
          <label>License<input value={fields.license} onChange={(event) => updateField('license', event.target.value)} /></label>
          <label>Revision<input value={fields.revision} onChange={(event) => updateField('revision', event.target.value)} /></label>
          <label className="wide">Provenance<textarea value={fields.provenance} onChange={(event) => updateField('provenance', event.target.value)} /></label>
        </div>
        <fieldset className="forge-task-editor"><legend>New task</legend>
          <div className="forge-study-metadata">
            <label>Task ID<input value={draft.id} onChange={(event) => setDraft((current) => ({ ...current, id: event.target.value }))} /></label>
            <label>Type<select value={draft.type} onChange={(event) => setDraft((current) => ({ ...current, type: event.target.value as Task['type'] }))}><option value="multiple-choice">Multiple choice</option><option value="spatial">Spatial identification</option></select></label>
            <label>Viewer slide<select value={draft.slideId} onChange={(event) => setDraft((current) => ({ ...current, slideId: event.target.value }))}>{slides.map((slide) => <option key={slide.id} value={slide.id}>{slide.displayName}</option>)}</select></label>
            <label className="wide">Prompt<textarea value={draft.prompt} onChange={(event) => setDraft((current) => ({ ...current, prompt: event.target.value }))} /></label>
            {draft.type === 'multiple-choice' ? <><label className="wide">Options, one per line<textarea value={draft.options?.join('\n')} onChange={(event) => setDraft((current) => ({ ...current, options: event.target.value.split('\n') }))} /></label><label>Explicit answer<input value={draft.answerKey} onChange={(event) => setDraft((current) => ({ ...current, answerKey: event.target.value }))} /></label></> : <div className="forge-spatial-grid"><label>X<input type="number" min="0" max="1" step=".01" value={draft.targetX ?? .4} onChange={(event) => setDraft((current) => ({ ...current, targetX: Number(event.target.value) }))} /></label><label>Y<input type="number" min="0" max="1" step=".01" value={draft.targetY ?? .4} onChange={(event) => setDraft((current) => ({ ...current, targetY: Number(event.target.value) }))} /></label><label>Width<input type="number" min=".01" max="1" step=".01" value={draft.targetWidth ?? .15} onChange={(event) => setDraft((current) => ({ ...current, targetWidth: Number(event.target.value) }))} /></label><label>Height<input type="number" min=".01" max="1" step=".01" value={draft.targetHeight ?? .15} onChange={(event) => setDraft((current) => ({ ...current, targetHeight: Number(event.target.value) }))} /></label><label>Tolerance<input type="number" min=".01" max=".5" step=".01" value={draft.tolerance ?? .06} onChange={(event) => setDraft((current) => ({ ...current, tolerance: Number(event.target.value) }))} /></label></div>}
            <label className="wide">Hints, one per line (maximum 3)<textarea value={draft.hints.join('\n')} onChange={(event) => setDraft((current) => ({ ...current, hints: event.target.value.split('\n') }))} /></label>
            <label className="wide">Faculty explanation<textarea value={draft.explanation} onChange={(event) => setDraft((current) => ({ ...current, explanation: event.target.value }))} /></label>
            <label>Source title<input value={draft.sources[0].title} onChange={(event) => setDraft((current) => ({ ...current, sources: [{ ...current.sources[0], title: event.target.value }] }))} /></label>
            <label>HTTPS source URL<input value={draft.sources[0].url} onChange={(event) => setDraft((current) => ({ ...current, sources: [{ ...current.sources[0], url: event.target.value }] }))} /></label>
          </div>
          <button type="button" onClick={addTask}><Plus aria-hidden="true" /> Add task</button>
        </fieldset>
        <div className="forge-study-actions"><button type="button" onClick={() => fileInput.current?.click()}><FileArrowUp aria-hidden="true" /> Import bounded CSV</button><input ref={fileInput} className="forge-visually-hidden" type="file" accept=".csv,text/csv" onChange={(event) => void importCsv(event.currentTarget.files?.[0])} /><button type="button" disabled={busy || !tasks.length} onClick={() => void startPreview()}><CheckCircle aria-hidden="true" /> Start faculty preview ({tasks.length})</button></div>
        {tasks.length ? <ol className="forge-task-list">{tasks.map((task) => <li key={task.id}><strong>{task.prompt}</strong><span>{task.type} · {slides.find((slide) => slide.id === task.slideId)?.displayName}</span></li>)}</ol> : null}
      </section>
      <aside className="forge-study-preview" aria-label="Exact learner preview">
        {preview && previewTask ? <><span className="forge-study-eyebrow">Exact learner projection</span><h2 ref={previewHeading} tabIndex={-1}>Task {previewIndex + 1} of {preview.canonicalCore.tasks.length}</h2><h3>{previewTask.prompt}</h3>
          {previewTask.options ? <ol>{previewTask.options.map((option) => <li key={option}>{option}{option === previewTask.answerKey ? <strong> Correct key</strong> : null}</li>)}</ol> : <p>Spatial target: {previewTask.targetX}, {previewTask.targetY}, {previewTask.targetWidth} × {previewTask.targetHeight}; tolerance {previewTask.tolerance}</p>}
          {previewTask.hints.map((hint, index) => <p key={hint}><strong>Hint {index + 1}:</strong> {hint}</p>)}<p><strong>Explanation:</strong> {previewTask.explanation}</p><ul>{previewTask.sources.map((source) => <li key={source.url}><a href={source.url}>{source.title}</a></li>)}</ul>
          <h3>All optional local-AI action cards</h3><div className="forge-action-grid">{ACTIONS.map(([action, english, thai, reason]) => <article key={action}><Brain aria-hidden="true" /><strong>{english}</strong><span>{thai}</span><code>{reason}</code><small>No probability is displayed.</small></article>)}</div>
          <nav className="forge-preview-nav"><button type="button" disabled={previewIndex === 0} onClick={() => setPreviewIndex((value) => value - 1)}><ArrowLeft /> Previous</button><button type="button" disabled={previewIndex === preview.canonicalCore.tasks.length - 1} onClick={() => setPreviewIndex((value) => value + 1)}>Next <ArrowRight /></button></nav>
          <label className="forge-preview-attest"><input type="checkbox" checked={keyboardChecked} onChange={(event) => setKeyboardChecked(event.target.checked)} /> I checked keyboard focus order, every faculty response, every source, and the English/Thai action copy.</label>
          <button type="button" className="forge-primary" disabled={busy || visited.size !== preview.canonicalCore.tasks.length || !keyboardChecked} onClick={() => void attest()}><CheckCircle /> Attest and save exact checksum</button>
        </> : <><span className="forge-study-eyebrow">Faculty gate</span><h2>Preview required</h2><p>After authoring, inspect every learner task and every bounded action before an immutable version can be saved.</p></>}
        {saved ? <button type="button" className="forge-primary" disabled={busy} onClick={() => void publish(saved)}><UploadSimple /> Publish v{saved.version} privately to Viewer</button> : null}
        {savedPacks.length ? <section><h3>Saved immutable packs</h3>{savedPacks.map((pack) => <button type="button" key={pack.checksum} disabled={busy} onClick={() => void publish(pack)}>{pack.title} · v{pack.version}</button>)}</section> : null}
        {notice ? <p role="status">{notice}</p> : null}{error ? <p role="alert" className="forge-study-error">{error}</p> : null}
      </aside>
    </div>
  </main>
}

function csvRows(text: string): string[][] {
  const rows: string[][] = []; let row: string[] = []; let cell = ''; let quoted = false
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index]
    if (character === '"') { if (quoted && text[index + 1] === '"') { cell += '"'; index += 1 } else quoted = !quoted }
    else if (character === ',' && !quoted) { row.push(cell.trim()); cell = '' }
    else if ((character === '\r' || character === '\n') && !quoted) { if (character === '\r' && text[index + 1] === '\n') index += 1; row.push(cell.trim()); if (row.some(Boolean)) rows.push(row); row = []; cell = '' }
    else cell += character
  }
  if (quoted) throw new Error('CSV contains an unclosed quoted field.')
  row.push(cell.trim()); if (row.some(Boolean)) rows.push(row)
  return rows
}

function message(error: unknown) { return error instanceof Error ? error.message : 'Unexpected Study Pack error.' }
