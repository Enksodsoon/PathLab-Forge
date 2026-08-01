import {
  ArrowLeft,
  CheckCircle,
  Crosshair,
  House,
  Lightbulb,
  MagnifyingGlassMinus,
  MagnifyingGlassPlus,
  Play,
  SkipForward,
  Stop,
} from '@phosphor-icons/react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type OpenSeadragon from 'openseadragon'

import * as api from './api'
import type { Dataset, PivotManifestSummary, PivotScore, PivotSession, PivotTask } from './api'
import { SlideViewer } from './SlideViewer'
import type { SourcePoint, ViewportSnapshot } from './SlideViewer'

interface PivotWorkspaceProps {
  dataset: Dataset
  tileSource: string
  cropX?: number
  cropY?: number
  downsample?: number
  onClose: () => void
}

interface NavigationMetrics {
  panDistance: number
  zoomReversals: number
  previous?: ViewportSnapshot
  zoomDirection: number
}

const EMPTY_METRICS: NavigationMetrics = {
  panDistance: 0,
  zoomReversals: 0,
  zoomDirection: 0,
}

export function PivotWorkspace({
  dataset,
  tileSource,
  cropX = 0,
  cropY = 0,
  downsample = 0,
  onClose,
}: PivotWorkspaceProps) {
  const [manifest, setManifest] = useState<PivotManifestSummary>()
  const [session, setSession] = useState<PivotSession>()
  const [presentedTask, setPresentedTask] = useState<PivotTask | null>(null)
  const [selectedPoint, setSelectedPoint] = useState<SourcePoint>()
  const [score, setScore] = useState<PivotScore>()
  const [hint, setHint] = useState('')
  const [confidence, setConfidence] = useState(2)
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [viewer, setViewer] = useState<OpenSeadragon.Viewer | null>(null)
  const viewerRef = useRef<OpenSeadragon.Viewer | null>(null)
  const taskStartedAt = useRef(Date.now())
  const metrics = useRef<NavigationMetrics>({ ...EMPTY_METRICS })
  const retryTimer = useRef<number | undefined>(undefined)
  const preparationAttempts = useRef(0)

  const resetTask = useCallback((next: PivotTask | null) => {
    setPresentedTask(next)
    setSelectedPoint(undefined)
    setScore(undefined)
    setHint('')
    setConfidence(2)
    taskStartedAt.current = Date.now()
    metrics.current = { ...EMPTY_METRICS }
    viewerRef.current?.viewport.goHome(true)
  }, [])

  const rememberViewer = useCallback((nextViewer: OpenSeadragon.Viewer | null) => {
    viewerRef.current = nextViewer
    setViewer(nextViewer)
  }, [])

  const restoreSession = useCallback(async () => {
    try {
      const restored = await api.pivotSession(dataset.id)
      setSession(restored)
      resetTask(restored.state === 'ACTIVE' ? restored.currentTask : null)
    } catch {
      setSession(undefined)
      setPresentedTask(null)
    }
  }, [dataset.id, resetTask])

  const prepare = useCallback(async () => {
    window.clearTimeout(retryTimer.current)
    setBusy(true)
    setError('')
    try {
      let status = await api.pivotStatus(dataset.id)
      if (status.status !== 'READY') {
        status = await api.compilePivot(dataset.id)
      }
      preparationAttempts.current = 0
      setManifest(status)
      await restoreSession()
      setBusy(false)
    } catch (nextError) {
      const detail = message(nextError)
      if (detail.toLowerCase().includes('preview') && preparationAttempts.current < 20) {
        preparationAttempts.current += 1
        setError('Preparing the reusable slide pyramid before compiling training tasks…')
        retryTimer.current = window.setTimeout(() => void prepare(), 1_500)
        return
      }
      setError(detail)
      setBusy(false)
    }
  }, [dataset.id, restoreSession])

  useEffect(() => {
    void prepare()
    return () => window.clearTimeout(retryTimer.current)
  }, [prepare])

  const start = async () => {
    setBusy(true)
    setError('')
    try {
      const next = await api.startPivotSession(dataset.id)
      setSession(next)
      resetTask(next.currentTask)
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setBusy(false)
    }
  }

  const submit = async () => {
    if (!selectedPoint || !presentedTask || score) return
    setBusy(true)
    setError('')
    try {
      const result = await api.submitPivot(dataset.id, {
        x: selectedPoint.x,
        y: selectedPoint.y,
        elapsedMs: Math.max(0, Date.now() - taskStartedAt.current),
        panDistance: metrics.current.panDistance,
        zoomReversals: metrics.current.zoomReversals,
        confidence,
      })
      setScore(result)
      setSession(result.session)
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setBusy(false)
    }
  }

  const requestHint = async () => {
    setBusy(true)
    try {
      const result = await api.hintPivot(dataset.id)
      setHint(result.text)
      setSession(result.session)
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setBusy(false)
    }
  }

  const skip = async () => {
    setBusy(true)
    try {
      const next = await api.skipPivot(dataset.id)
      setSession(next)
      resetTask(next.currentTask)
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setBusy(false)
    }
  }

  const end = async () => {
    setBusy(true)
    try {
      const next = await api.endPivot(dataset.id)
      setSession(next)
      resetTask(null)
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setBusy(false)
    }
  }

  const nextTask = () => {
    resetTask(session?.state === 'ACTIVE' ? session.currentTask : null)
  }

  const trackViewport = useCallback((snapshot: ViewportSnapshot) => {
    const previous = metrics.current.previous
    if (previous) {
      const movement = Math.hypot(snapshot.x - previous.x, snapshot.y - previous.y)
      if (Number.isFinite(movement)) metrics.current.panDistance += movement
      const difference = snapshot.zoom - previous.zoom
      const direction = Math.abs(difference) < 0.000_1 ? 0 : Math.sign(difference)
      if (direction && metrics.current.zoomDirection && direction !== metrics.current.zoomDirection) {
        metrics.current.zoomReversals += 1
      }
      if (direction) metrics.current.zoomDirection = direction
    }
    metrics.current.previous = snapshot
  }, [])

  const targetAnnotation = useMemo<api.AnnotationRecord[]>(() => score ? [{
    id: 'pivot-answer',
    type: 'rectangle',
    geometry: `${score.target.x},${score.target.y};${score.target.x + score.target.width},${score.target.y + score.target.height}`,
    label: 'Correct source region',
    color: '#17b897',
    createdAt: Date.now(),
  }] : [], [score])

  const complete = session?.state === 'COMPLETED' && !score
  const readyToStart = manifest?.status === 'READY' && !session

  return (
    <main className="forge-pivot-workspace" aria-label="PIVOT training workspace">
      <header className="forge-pivot-topbar">
        <button type="button" className="forge-pivot-back" aria-label="Back to Forge" onClick={onClose}>
          <ArrowLeft /> <span>Back to Forge</span>
        </button>
        <div className="forge-pivot-identity">
          <strong>PathLab Forge</strong>
          <span>{dataset.displayName}</span>
        </div>
        <div className="forge-pivot-mode"><Crosshair /> PIVOT training</div>
      </header>

      {busy && !presentedTask ? (
        <PivotPreparation
          tileSource={tileSource}
          dataset={dataset}
          cropX={cropX}
          cropY={cropY}
          downsample={downsample}
          onViewer={rememberViewer}
          detail={error}
        />
      ) : readyToStart ? (
        <PivotWelcome manifest={manifest} error={error} busy={busy} onStart={() => void start()} onRetry={() => void prepare()} />
      ) : complete ? (
        <PivotSummary session={session} onRestart={() => void start()} onClose={onClose} />
      ) : presentedTask && session ? (
        <>
          <div className="forge-pivot-body">
            <aside className="forge-pivot-query" aria-label="Current training task">
              <div className="forge-pivot-task-count">Task {presentedTask.index} of {presentedTask.total}</div>
              <h1>Find this region on the slide</h1>
              <p>Navigate from the overview and select the exact source of this tissue.</p>
              <figure>
                <img src={presentedTask.queryUrl} alt="Tissue region to locate" />
                <figcaption>This is the region to find.</figcaption>
              </figure>
              <p className="forge-pivot-safety">No diagnostic labels are used.</p>
              {hint ? <div className="forge-pivot-hint" role="status"><Lightbulb /> {hint}</div> : null}
            </aside>

            <section className="forge-pivot-stage" aria-label="Training slide viewer">
              <SlideViewer
                tileSource={tileSource}
                sourceWidth={dataset.width}
                sourceHeight={dataset.height}
                cropX={cropX}
                cropY={cropY}
                downsample={downsample}
                annotations={targetAnnotation}
                selectedPoint={selectedPoint}
                onSelectLocation={score ? undefined : setSelectedPoint}
                onViewportSettled={trackViewport}
                onReady={rememberViewer}
              />
              <div className="forge-pivot-viewer-tools" aria-label="Training viewer controls">
                <button type="button" aria-label="Zoom out" onClick={() => viewer?.viewport.zoomBy(.67)}><MagnifyingGlassMinus /></button>
                <button type="button" aria-label="Reset slide view" onClick={() => viewer?.viewport.goHome()}><House /></button>
                <button type="button" aria-label="Zoom in" onClick={() => viewer?.viewport.zoomBy(1.5)}><MagnifyingGlassPlus /></button>
              </div>
              {!selectedPoint && !score ? (
                <div className="forge-pivot-stage-prompt">Click the matching location when you find it</div>
              ) : null}
            </section>

            <aside className="forge-pivot-progress" aria-label="Training progress">
              <div className="forge-pivot-difficulty">
                <span>Difficulty</span>
                <strong>{presentedTask.difficultyLabel}</strong>
              </div>
              <dl>
                <div><dt>Completed</dt><dd>{session.completedTasks} / {session.totalTasks}</dd></div>
                <div><dt>Hints used</dt><dd>{session.hintsUsed}</dd></div>
                <div><dt>Scale gap</dt><dd>{presentedTask.scaleGap}×</dd></div>
              </dl>
              <h2>Recent performance</h2>
              <div className="forge-pivot-attempts">
                {session.recentAttempts.length ? session.recentAttempts.map((attempt, index) => (
                  <div key={`${attempt.taskId}-${index}`}>
                    <span className={`rating-${attempt.rating.toLowerCase()}`}><CheckCircle /></span>
                    <strong>Task {Math.max(1, presentedTask.index - session.recentAttempts.length + index)}</strong>
                    <small>{attempt.normalizedError.toFixed(2)} widths</small>
                  </div>
                )) : <p>Your scored attempts will appear here.</p>}
              </div>
              {score ? <PivotResult score={score} session={session} onNext={nextTask} /> : null}
              {error ? <div className="forge-pivot-error" role="alert">{error}</div> : null}
            </aside>
          </div>

          <footer className="forge-pivot-actions">
            <fieldset disabled={Boolean(score) || busy}>
              <legend>Confidence</legend>
              {['Low', 'Medium', 'High', 'Very high'].map((label, index) => (
                <button
                  type="button"
                  key={label}
                  className={confidence === index + 1 ? 'active' : ''}
                  aria-pressed={confidence === index + 1}
                  onClick={() => setConfidence(index + 1)}
                >{label}</button>
              ))}
            </fieldset>
            <button
              type="button"
              className="forge-pivot-submit"
              disabled={!selectedPoint || Boolean(score) || busy}
              onClick={() => void submit()}
            ><Crosshair /> Submit location</button>
            <button type="button" disabled={Boolean(score) || busy} onClick={() => void requestHint()}><Lightbulb /> Hint</button>
            <button type="button" disabled={Boolean(score) || busy} onClick={() => void skip()}><SkipForward /> Skip</button>
            <button type="button" disabled={busy} onClick={() => void end()}><Stop /> End session</button>
          </footer>
        </>
      ) : (
        <PivotWelcome manifest={manifest} error={error} busy={busy} onStart={() => void start()} onRetry={() => void prepare()} />
      )}
    </main>
  )
}

function PivotPreparation({
  tileSource,
  dataset,
  cropX,
  cropY,
  downsample,
  onViewer,
  detail,
}: {
  tileSource: string
  dataset: Dataset
  cropX: number
  cropY: number
  downsample: number
  onViewer: (viewer: OpenSeadragon.Viewer | null) => void
  detail: string
}) {
  return (
    <section className="forge-pivot-preparing" aria-live="polite">
      <div className="forge-pivot-preparing-viewer" aria-hidden="true">
        <SlideViewer
          tileSource={tileSource}
          sourceWidth={dataset.width}
          sourceHeight={dataset.height}
          cropX={cropX}
          cropY={cropY}
          downsample={downsample}
          onReady={onViewer}
        />
      </div>
      <div>
        <span className="forge-pivot-spinner" aria-hidden="true" />
        <h1>Building coordinate-grounded tasks</h1>
        <p>{detail || 'Sampling the reusable slide pyramid and rejecting blank or low-information regions.'}</p>
        <small>The original slide stays unchanged. No annotations or diagnostic labels are created.</small>
      </div>
    </section>
  )
}

function PivotWelcome({
  manifest,
  error,
  busy,
  onStart,
  onRetry,
}: {
  manifest?: PivotManifestSummary
  error: string
  busy: boolean
  onStart: () => void
  onRetry: () => void
}) {
  return (
    <section className="forge-pivot-welcome">
      <Crosshair />
      <h1>Your annotation-free training set is ready</h1>
      <p>Find high-power regions on the complete slide. Every answer is scored from the image’s exact source coordinates.</p>
      {manifest?.status === 'READY' ? (
        <div className="forge-pivot-ready-metrics">
          <span><strong>{manifest.totalTasks}</strong> tasks</span>
          <span><strong>{manifest.inspectedCandidates}</strong> candidates checked</span>
          <span><strong>{manifest.generationMs}</strong> ms generated</span>
        </div>
      ) : null}
      {error ? <div className="forge-pivot-error" role="alert">{error}</div> : null}
      {manifest?.status === 'READY' ? (
        <button type="button" className="forge-pivot-submit" disabled={busy} onClick={onStart}><Play /> Start session</button>
      ) : (
        <button type="button" className="forge-pivot-submit" disabled={busy} onClick={onRetry}>Retry task build</button>
      )}
      <small>Research mode · non-diagnostic · local only</small>
    </section>
  )
}

function PivotResult({ score, session, onNext }: { score: PivotScore; session: PivotSession; onNext: () => void }) {
  const title = score.rating === 'MATCH' ? 'Good match' : score.rating === 'CLOSE' ? 'Close region' : 'Different region'
  return (
    <section className={`forge-pivot-result result-${score.rating.toLowerCase()}`} aria-live="polite">
      <CheckCircle />
      <h2>{title}</h2>
      <span>Distance from correct source</span>
      <strong>{score.normalizedError.toFixed(2)} target widths</strong>
      <p>The outlined area shows the exact coordinate-grounded answer.</p>
      <button type="button" className="forge-pivot-submit" onClick={onNext}>
        {session.state === 'ACTIVE' ? 'Next task' : 'View summary'}
      </button>
    </section>
  )
}

function PivotSummary({ session, onRestart, onClose }: { session: PivotSession; onRestart: () => void; onClose: () => void }) {
  const matches = session.recentAttempts.filter((attempt) => attempt.rating === 'MATCH').length
  return (
    <section className="forge-pivot-welcome forge-pivot-summary">
      <CheckCircle />
      <h1>Training session complete</h1>
      <p>You completed {session.completedTasks} tasks and skipped {session.skippedTasks}. Recent exact matches: {matches}.</p>
      <div>
        <button type="button" className="forge-pivot-submit" onClick={onRestart}><Play /> Start another session</button>
        <button type="button" onClick={onClose}><ArrowLeft /> Return to Forge</button>
      </div>
    </section>
  )
}

function message(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
