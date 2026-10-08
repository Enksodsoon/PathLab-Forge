import { useEffect, useRef, useState, type ReactNode } from 'react'
import { pixelsMatch, type TeachingPixels } from './teachingAssociations'

export interface StudySource { title: string; url: string }
export interface StudyTask {
  id: string; type: string; slideId: string; prompt: string; hints: string[]; explanation: string; sources: StudySource[]
  options?: string[]; answerKey?: string; targetX?: number; targetY?: number; targetWidth?: number; targetHeight?: number; tolerance?: number
}
export interface StudySlide { viewerSlideId: string; sha256: string; displayName: string }
export interface StudyDefinition {
  schema: 'pathlab.study-pack/1'; packKey: string; version: number; title: string; author: string; license: string
  provenance: string; revision: string; languages: string[]; slides: StudySlide[]; tasks: StudyTask[]
}
export interface StudyDraftRecord {
  id: string; name: string; revision: number; definition: StudyDefinition; associations: Record<string, unknown>
  issues: string[]; previewChecksum: string; reviewedTaskIds: string[]; approvedChecksum: string; updatedAt: number
}
export interface StudyAuthoringProps {
  drafts: StudyDraftRecord[]; slides?: StudySlide[]
  onLoad?: (id: string) => Promise<StudyDraftRecord>
  onCreate: (name: string) => Promise<StudyDraftRecord>
  onSave: (draft: StudyDraftRecord, expectedRevision: number) => Promise<StudyDraftRecord>
  onDuplicate: (id: string, name: string, nextVersion: boolean) => Promise<StudyDraftRecord>
  onHistory: (id: string) => Promise<StudyDraftRecord[]>
  onRecover: (id: string, historicalRevision: number, expectedRevision: number) => Promise<StudyDraftRecord>
  onPreview: (id: string, revision: number) => Promise<StudyDraftRecord>
  onReviewTask: (id: string, revision: number, checksum: string, taskId: string, pixels: TeachingPixels) => Promise<StudyDraftRecord>
  onApprove: (id: string, revision: number, checksum: string) => Promise<StudyDraftRecord>
  onImport: (format: 'json' | 'csv', text: string) => Promise<StudyDraftRecord>
  onImportQuestions: (id: string, revision: number, format: string, text: string, slideId: string) => Promise<StudyDraftRecord>
  onExport: (id: string, format: 'json' | 'csv' | 'approved', checksum?: string) => void
  onPublish?: (checksum: string) => Promise<unknown>
  teachingArtifacts?: Array<{ datasetId: string; artifactRevision: string; displayName: string }>
  onAssociateTeachingSlide?: (id: string, revision: number, referenceId: string, datasetId: string, artifactRevision: string) => Promise<StudyDraftRecord>
  canPreviewSlide?: (slide: StudySlide | undefined) => boolean
  renderSlide?: (slide: StudySlide | undefined, onLocation: (x: number, y: number) => void,
    onPixelsLoaded: (pixels: TeachingPixels | null) => void, draft: StudyDraftRecord) => ReactNode
  onCaptureSpatial?: (slide: StudySlide | undefined, draft: StudyDraftRecord) => Promise<{ targetX: number; targetY: number; targetWidth: number; targetHeight: number }>
}
const content = (draft: StudyDraftRecord) => JSON.stringify([draft.name, draft.definition, draft.associations])
const message = (cause: unknown) => cause instanceof Error ? cause.message : String(cause)

export function StudyAuthoring(props: StudyAuthoringProps) {
  const [draft, setDraft] = useState<StudyDraftRecord | undefined>(props.drafts[0])
  const [name, setName] = useState('')
  const [taskIndex, setTaskIndex] = useState(0)
  const [previewIndex, setPreviewIndex] = useState(0)
  const [history, setHistory] = useState<StudyDraftRecord[]>([])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('Drafts stay on this computer. Faculty review and approval precede publication.')
  const [error, setError] = useState('')
  const [importFormat, setImportFormat] = useState('csv')
  const [loadedPixels, setLoadedPixels] = useState<TeachingPixels | null>(null)
  const local = useRef(draft)
  const server = useRef(draft)
  const saving = useRef<Promise<void> | null>(null)
  const saveCallback = useRef(props.onSave)
  local.current = draft; saveCallback.current = props.onSave
  const contentKey = draft ? content(draft) : ''

  const adopt = (value: StudyDraftRecord) => {
    setLoadedPixels(null)
    server.current = value; local.current = value; setDraft(value); setTaskIndex(0); setPreviewIndex(0); setHistory([])
  }
  useEffect(() => { if (!local.current && props.drafts[0]) adopt(props.drafts[0]) }, [props.drafts])
  const flush = () => {
    if (saving.current) return saving.current
    saving.current = (async () => {
      while (local.current && server.current && content(local.current) !== content(server.current)) {
        const snapshot = local.current
        const expected = server.current.revision
        setNotice('Saving draft…')
        const saved = await saveCallback.current(snapshot, expected)
        server.current = saved
        if (local.current?.id !== snapshot.id) break
        if (content(local.current) === content(snapshot)) {
          local.current = saved; setDraft(saved)
        } else {
          local.current = { ...local.current, revision: saved.revision }
          setDraft(local.current)
        }
      }
      setNotice('Draft saved locally.')
    })().catch((cause: unknown) => { setError(message(cause)); setNotice('Draft was not saved. Keep this window open and reload the current revision before resolving a conflict.'); throw cause })
      .finally(() => { saving.current = null })
    return saving.current
  }
  useEffect(() => {
    if (!draft || !server.current || contentKey === content(server.current)) return
    const timer = window.setTimeout(() => { void flush().catch(() => {}) }, 600)
    return () => window.clearTimeout(timer)
    // Saving uses refs so changes arriving during a request are saved in order.
  }, [contentKey])
  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (saving.current || (local.current && server.current && content(local.current) !== content(server.current))) { event.preventDefault(); event.returnValue = '' }
    }
    window.addEventListener('beforeunload', guard); return () => window.removeEventListener('beforeunload', guard)
  }, [])
  const action = async (run: () => Promise<void>) => {
    setBusy(true); setError('')
    try { await run() } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
  }
  const editDefinition = (changes: Partial<StudyDefinition>) => {
    if (!draft) return
    const next = { ...draft, definition: { ...draft.definition, ...changes }, previewChecksum: '', reviewedTaskIds: [], approvedChecksum: '' }
    local.current = next; setDraft(next); setError('')
    setLoadedPixels(null)
  }
  const editTask = (changes: Partial<StudyTask>) => {
    if (!draft) return
    editDefinition({ tasks: draft.definition.tasks.map((task, index) => index === taskIndex ? { ...task, ...changes } : task) })
  }
  const importFile = async (file: File | undefined, questions: boolean) => {
    if (!file) return
    if (file.size > 2 * 1024 * 1024) { setError('Import exceeds 2 MiB.'); return }
    await action(async () => {
      await flush(); const text = await file.text()
      if (questions) {
        if (!server.current) throw new Error('Create a draft first.')
        adopt(await props.onImportQuestions(server.current.id, server.current.revision, importFormat, text, server.current.definition.slides[0]?.viewerSlideId || ''))
      } else adopt(await props.onImport(file.name.toLowerCase().endsWith('.csv') ? 'csv' : 'json', text))
    })
  }
  const task = draft?.definition.tasks[taskIndex]
  const previewTask = draft?.definition.tasks[previewIndex]
  const previewReady = Boolean(draft?.previewChecksum)
  const previewSlide = draft?.definition.slides.find((slide) => slide.viewerSlideId === previewTask?.slideId)
  const previewSlideReady = Boolean(draft && pixelsMatch(loadedPixels, draft.previewChecksum, previewSlide, draft.associations))
  return <section aria-label="Offline Study Pack authoring" className="forge-inspector-section">
    <h2>Study Pack authoring</h2>
    <p>Author questions, locations, hints and explanations manually. Drafts, imports and faculty previews work offline.</p>
    <p role="status">{notice}</p>{error ? <p role="alert">{error}</p> : null}
    <fieldset disabled={busy}><legend>Named drafts</legend>
      <label>Draft name <input value={name} maxLength={240} onChange={(event) => setName(event.target.value)} /></label>
      <button type="button" disabled={!name.trim()} onClick={() => { void action(async () => { await flush(); adopt(await props.onCreate(name.trim())) }) }}>New draft</button>
      <label>Open draft <select value={draft?.id || ''} onChange={(event) => {
        const next = props.drafts.find((value) => value.id === event.target.value)
        if (next) void action(async () => { await flush(); adopt(props.onLoad ? await props.onLoad(next.id) : (server.current?.id === next.id ? server.current : next)) })
      }}><option value="">Choose saved draft</option>{props.drafts.map((value) => <option key={value.id} value={value.id}>{value.name}</option>)}
        {draft && !props.drafts.some((value) => value.id === draft.id) ? <option value={draft.id}>{draft.name}</option> : null}
      </select></label>
      <label>Import saved draft or pack (JSON/CSV) <input type="file" accept=".json,.csv" onChange={(event) => { void importFile(event.target.files?.[0], false); event.target.value = '' }} /></label>
    </fieldset>
    {draft ? <>
      <fieldset disabled={busy}><legend>Pack metadata</legend>
        <label>Saved draft name <input value={draft.name} maxLength={240} onChange={(event) => { const next = { ...draft, name: event.target.value, previewChecksum: '', reviewedTaskIds: [], approvedChecksum: '' }; local.current = next; setDraft(next) }} /></label>
        {(['packKey', 'title', 'author', 'license', 'provenance', 'revision'] as const).map((field) => <label key={field}>{field === 'packKey' ? 'Pack key' : field.charAt(0).toUpperCase() + field.slice(1)} <input value={draft.definition[field]} onChange={(event) => editDefinition({ [field]: event.target.value })} /></label>)}
        <label>Version <input type="number" min="1" step="1" value={draft.definition.version} onChange={(event) => editDefinition({ version: event.target.valueAsNumber })} /></label>
        <label>Languages <select value={draft.definition.languages.join(',')} onChange={(event) => editDefinition({ languages: event.target.value.split(',') })}><option value="en">English</option><option value="th">Thai</option><option value="en,th">English and Thai</option></select></label>
        <label>Add slide <select value="" onChange={(event) => {
          const slide = props.slides?.find((value) => value.viewerSlideId === event.target.value)
          if (slide && !draft.definition.slides.some((value) => value.viewerSlideId === slide.viewerSlideId)) editDefinition({ slides: [...draft.definition.slides, slide] })
        }}><option value="">Choose an available slide</option>{props.slides?.map((slide) => <option key={slide.viewerSlideId} value={slide.viewerSlideId}>{slide.displayName}</option>)}</select></label>
        {draft.definition.slides.map((slide) => <div key={slide.viewerSlideId}><p>{slide.displayName} · {slide.viewerSlideId.startsWith('local:') ? 'Local draft reference; awaiting Viewer delivery' : 'Viewer slide reference'} · {slide.sha256 ? `package checksum ${slide.sha256.slice(0, 12)}…` : 'Prepared package not associated yet'}</p>
          {props.onAssociateTeachingSlide ? <label>Exact local teaching artifact for {slide.displayName} <select value="" onChange={(event) => {
            const artifact = props.teachingArtifacts?.find((item) => `${item.datasetId}:${item.artifactRevision}` === event.target.value)
            if (artifact) void action(async () => { await flush(); if (server.current) adopt(await props.onAssociateTeachingSlide!(server.current.id,
              server.current.revision, slide.viewerSlideId, artifact.datasetId, artifact.artifactRevision)) })
          }}><option value="">Choose a prepared artifact or resolve its delivery</option>{props.teachingArtifacts?.map((artifact) => <option key={`${artifact.datasetId}:${artifact.artifactRevision}`} value={`${artifact.datasetId}:${artifact.artifactRevision}`}>{artifact.displayName} · {artifact.artifactRevision.slice(0, 8)}</option>)}</select></label> : null}
        </div>)}
        <button type="button" onClick={() => { void action(async () => { await flush(); if (server.current) adopt(await props.onDuplicate(server.current.id, `${server.current.name} copy`, false)) }) }}>Duplicate draft</button>
        <button type="button" onClick={() => { void action(async () => { await flush(); if (server.current) adopt(await props.onDuplicate(server.current.id, `${server.current.name} next version`, true)) }) }}>Start next version</button>
        <button type="button" onClick={() => { void action(async () => { await flush(); if (server.current) setHistory(await props.onHistory(server.current.id)) }) }}>Recovery history</button>
        {history.length ? <label>Recover revision <select value="" onChange={(event) => {
          const revision = Number(event.target.value)
          if (revision && server.current) void action(async () => { await flush(); if (server.current) adopt(await props.onRecover(server.current.id, revision, server.current.revision)) })
        }}><option value="">Choose earlier revision</option>{history.map((value) => <option key={value.revision} value={value.revision}>Revision {value.revision} · {new Date(value.updatedAt).toLocaleString()}</option>)}</select></label> : null}
      </fieldset>
      <fieldset disabled={busy}><legend>Manual questions</legend>
        <button type="button" onClick={() => {
          let number = 1; while (draft.definition.tasks.some((value) => value.id === `task-${number}`)) number++
          const next: StudyTask = { id: `task-${number}`, type: 'multiple-choice', slideId: draft.definition.slides[0]?.viewerSlideId || '', prompt: '', options: ['', ''], answerKey: '', hints: [], explanation: '', sources: [] }
          editDefinition({ tasks: [...draft.definition.tasks, next] }); setTaskIndex(draft.definition.tasks.length)
        }}>Add question</button>
        <label>Question <select value={taskIndex} onChange={(event) => setTaskIndex(Number(event.target.value))}>{draft.definition.tasks.map((value, index) => <option key={`${value.id}-${index}`} value={index}>{value.id || `Question ${index + 1}`} · {value.type}</option>)}</select></label>
        {task ? <>
          <label>Question id <input value={task.id} onChange={(event) => editTask({ id: event.target.value })} /></label>
          <label>Question type <select value={task.type} onChange={(event) => editTask({ type: event.target.value })}><option value="multiple-choice">Multiple choice</option><option value="spatial">Spatial location</option><option value="faculty-conversion">Faculty conversion required</option></select></label>
          <label>Question slide <select value={task.slideId} onChange={(event) => editTask({ slideId: event.target.value })}><option value="">Choose slide</option>{draft.definition.slides.map((slide) => <option key={slide.viewerSlideId} value={slide.viewerSlideId}>{slide.displayName}</option>)}</select></label>
          <label>Prompt <textarea value={task.prompt} onChange={(event) => editTask({ prompt: event.target.value })} /></label>
          {task.type === 'multiple-choice' ? <>
            <label>Choices (one per line) <textarea value={(task.options || []).join('\n')} onChange={(event) => editTask({ options: event.target.value.split('\n') })} /></label>
            <label>Explicit correct choice <select value={task.answerKey || ''} onChange={(event) => editTask({ answerKey: event.target.value })}><option value="">Choose the faculty key</option>{task.options?.map((choice, index) => <option key={index} value={choice}>{choice || `Empty choice ${index + 1}`}</option>)}</select></label>
          </> : null}
          {task.type === 'spatial' ? <>
            {(['targetX', 'targetY', 'targetWidth', 'targetHeight', 'tolerance'] as const).map((field) => <label key={field}>{field} (normalized 0–1) <input type="number" min="0" max={field === 'tolerance' ? '.5' : '1'} step=".001" value={task[field] ?? ''} onChange={(event) => editTask({ [field]: event.target.valueAsNumber })} /></label>)}
            {props.onCaptureSpatial ? <button type="button" onClick={() => { void action(async () => editTask(await props.onCaptureSpatial!(draft.definition.slides.find((slide) => slide.viewerSlideId === task.slideId), draft))) }}>Use selected ROI coordinates</button> : null}
          </> : null}
          <label>Hints (up to three, one per line) <textarea value={task.hints.join('\n')} onChange={(event) => editTask({ hints: event.target.value ? event.target.value.split('\n') : [] })} /></label>
          <label>Explanation <textarea value={task.explanation} onChange={(event) => editTask({ explanation: event.target.value })} /></label>
          {task.sources.map((source, index) => <div key={index}><label>Source {index + 1} title <input value={source.title} onChange={(event) => editTask({ sources: task.sources.map((value, at) => at === index ? { ...value, title: event.target.value } : value) })} /></label>
            <label>Source {index + 1} HTTPS URL <input type="url" value={source.url} onChange={(event) => editTask({ sources: task.sources.map((value, at) => at === index ? { ...value, url: event.target.value } : value) })} /></label></div>)}
          <button type="button" disabled={task.sources.length >= 10} onClick={() => editTask({ sources: [...task.sources, { title: '', url: '' }] })}>Add source</button>
          <button type="button" onClick={() => { editDefinition({ tasks: draft.definition.tasks.filter((_, index) => index !== taskIndex) }); setTaskIndex(0) }}>Remove this question</button>
        </> : null}
        <label>Question import format <select value={importFormat} onChange={(event) => setImportFormat(event.target.value)}><option value="csv">CSV</option><option value="json">JSON</option><option value="qti">QTI XML</option><option value="moodle">Moodle XML</option><option value="anki">Anki TSV</option></select></label>
        <label>Import supplied questions <input type="file" accept=".csv,.json,.xml,.tsv,.txt" onChange={(event) => { void importFile(event.target.files?.[0], true); event.target.value = '' }} /></label>
        <p>Unsupported response or scoring forms remain incomplete for faculty conversion. Supply keys, choices and sources explicitly.</p>
      </fieldset>
      <div>{draft.issues.map((issue) => <p key={issue} role="status">Incomplete: {issue}</p>)}</div>
      <button type="button" disabled={busy} onClick={() => { void action(async () => { await flush(); if (server.current) adopt(await props.onPreview(server.current.id, server.current.revision)) }) }}>Open exact faculty preview</button>
      <button type="button" disabled={busy} onClick={() => { void action(async () => { await flush(); if (server.current) props.onExport(server.current.id, 'json') }) }}>Export local draft JSON</button>
      <button type="button" disabled={busy} onClick={() => { void action(async () => { await flush(); if (server.current) props.onExport(server.current.id, 'csv') }) }}>Export authored CSV</button>
      {previewReady && previewTask ? <FacultyPreview draft={draft} task={previewTask} index={previewIndex} onIndex={setPreviewIndex} renderSlide={props.renderSlide} busy={busy} slideReady={previewSlideReady} onPixelsLoaded={setLoadedPixels}
        onReviewed={() => { void action(async () => { if (server.current && previewSlideReady && loadedPixels) { const next = await props.onReviewTask(server.current.id, server.current.revision, server.current.previewChecksum, previewTask.id, loadedPixels); server.current = next; local.current = next; setDraft(next) } }) }} /> : null}
      {previewReady ? <button type="button" disabled={busy || !previewSlideReady || draft.reviewedTaskIds.length !== draft.definition.tasks.length} onClick={() => { void action(async () => { if (server.current) { const next = await props.onApprove(server.current.id, server.current.revision, server.current.previewChecksum); server.current = next; local.current = next; setDraft(next); setNotice('Immutable faculty-approved export saved locally.') } }) }}>Approve this exact version</button> : null}
      {draft.approvedChecksum ? <><button type="button" onClick={() => props.onExport(draft.id, 'approved', draft.approvedChecksum)}>Export approved {draft.definition.slides.some((slide) => slide.viewerSlideId.startsWith('local:')) ? 'local draft' : 'Study Pack'}</button>
        {draft.definition.slides.some((slide) => slide.viewerSlideId.startsWith('local:')) ? <p>Local approval stays offline. Resolve actual delivered Viewer identities and preview again before publication.</p>
          : props.onPublish ? <button type="button" disabled={busy} onClick={() => { void action(async () => { await props.onPublish!(draft.approvedChecksum); setNotice('Viewer publication request completed.') }) }}>Publish approved pack to Viewer</button> : <p>The approved export stays local until an authorized Viewer publication is available.</p>}</> : null}
    </> : null}
  </section>
}

function FacultyPreview({ draft, task, index, onIndex, renderSlide, onReviewed, busy, slideReady, onPixelsLoaded }: { draft: StudyDraftRecord; task: StudyTask; index: number; onIndex: (index: number) => void; renderSlide?: StudyAuthoringProps['renderSlide']; onReviewed: () => void; busy: boolean; slideReady: boolean; onPixelsLoaded: (pixels: TeachingPixels | null) => void }) {
  const [selectedOption, setSelectedOption] = useState('')
  const [location, setLocation] = useState({ x: .5, y: .5 })
  const [feedback, setFeedback] = useState('')
  useEffect(() => { setSelectedOption(''); setFeedback('') }, [task.id, draft.previewChecksum])
  return <article aria-label="Exact faculty preview"><h3>Faculty preview {index + 1} of {draft.definition.tasks.length}</h3><p>Checksum: {draft.previewChecksum}</p>
    <p>{task.prompt}</p>{renderSlide?.(draft.definition.slides.find((slide) => slide.viewerSlideId === task.slideId), (x, y) => setLocation({ x, y }), onPixelsLoaded, draft)}
    {task.type === 'multiple-choice' ? <fieldset><legend>Try the authored choices</legend>{task.options?.map((choice, at) => <label key={at}><input type="radio" name={`preview-${task.id}`} checked={selectedOption === choice} onChange={() => setSelectedOption(choice)} />{choice}</label>)}</fieldset>
      : <div><label>Preview x <input type="number" min="0" max="1" step=".001" value={location.x} onChange={(event) => setLocation({ ...location, x: event.target.valueAsNumber })} /></label><label>Preview y <input type="number" min="0" max="1" step=".001" value={location.y} onChange={(event) => setLocation({ ...location, y: event.target.valueAsNumber })} /></label></div>}
    <button type="button" onClick={() => { try { const score = scoreStudyTask(task, { selectedOption, ...location }); setFeedback(score.correct ? 'Matches the faculty key.' : 'Does not match the faculty key.') } catch (cause) { setFeedback(message(cause)) } }}>Check authored scoring</button>
    {feedback ? <p role="status">{feedback}</p> : null}
    <p>Faculty key: {task.type === 'multiple-choice' ? task.answerKey : `${task.targetX}, ${task.targetY}; ${task.targetWidth} × ${task.targetHeight}; tolerance ${task.tolerance}`}</p>
    <p>{task.explanation}</p>{task.hints.map((hint, at) => <p key={at}>Hint {at + 1}: {hint}</p>)}
    {task.sources.map((source, at) => <p key={at}>{source.title} · {source.url}</p>)}
    {!slideReady ? <p role="status">Teaching slide preview is unavailable. Open its exact local artifact before confirming faculty review.</p> : null}
    <button type="button" disabled={busy || !slideReady} onClick={onReviewed}>I reviewed this task, key, hints and sources</button>
    <button type="button" disabled={index === 0} onClick={() => onIndex(index - 1)}>Previous task</button><button type="button" disabled={index + 1 >= draft.definition.tasks.length} onClick={() => onIndex(index + 1)}>Next task</button>
  </article>
}
export function scoreStudyTask(task: StudyTask, submission: { selectedOption?: string; x?: number; y?: number }) {
  if (task.type === 'multiple-choice') {
    if (typeof task.answerKey !== 'string' || !task.answerKey || !task.options?.includes(task.answerKey)) throw new Error('Faculty key is incomplete.')
    return { correct: submission.selectedOption === task.answerKey, normalizedError: null }
  }
  if (task.type !== 'spatial') throw new Error('This task requires faculty conversion.')
  const { x, y } = submission
  if (!Number.isFinite(x) || !Number.isFinite(y) || x! < 0 || x! > 1 || y! < 0 || y! > 1) throw new Error('Spatial coordinates must be finite values from zero to one.')
  const left = task.targetX!, top = task.targetY!, width = task.targetWidth!, height = task.targetHeight!, tolerance = task.tolerance!
  if (![left, top, width, height, tolerance].every(Number.isFinite) || left < 0 || top < 0 || width <= 0 || height <= 0 || left + width > 1 || top + height > 1 || tolerance <= 0 || tolerance > .5) throw new Error('Faculty spatial target is incomplete.')
  return { correct: x! >= left - tolerance && x! <= left + width + tolerance && y! >= top - tolerance && y! <= top + height + tolerance,
    normalizedError: Math.min(1, Math.hypot(x! - left - width / 2, y! - top - height / 2) / Math.sqrt(2)) }
}
