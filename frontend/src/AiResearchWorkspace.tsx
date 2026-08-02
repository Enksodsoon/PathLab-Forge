import {
  ArrowLeft,
  Brain,
  Crosshair,
  Flask,
  MagnifyingGlassMinus,
  MagnifyingGlassPlus,
  Play,
  Warning,
} from '@phosphor-icons/react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import type OpenSeadragon from 'openseadragon'

import * as api from './api'
import type { AiResearchResult, AiResearchStatus, Dataset } from './api'
import { SlideViewer } from './SlideViewer'

interface AiResearchWorkspaceProps {
  dataset: Dataset
  tileSource: string
  cropX?: number
  cropY?: number
  downsample?: number
  onClose: () => void
}

export function AiResearchWorkspace({
  dataset,
  tileSource,
  cropX = 0,
  cropY = 0,
  downsample = 0,
  onClose,
}: AiResearchWorkspaceProps) {
  const [status, setStatus] = useState<AiResearchStatus>()
  const [result, setResult] = useState<AiResearchResult>()
  const [selectedRegionId, setSelectedRegionId] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [viewer, setViewer] = useState<OpenSeadragon.Viewer | null>(null)

  const acceptResult = useCallback((next: AiResearchResult) => {
    setResult(next)
    setSelectedRegionId(
      next.auto_selected_region_id
        || next.suspected_regions.find((region) => region.auto_selected)?.id
        || next.suspected_regions[0]?.id,
    )
  }, [])

  useEffect(() => {
    let cancelled = false
    void api.aiResearchStatus().then((next) => {
      if (!cancelled) setStatus(next)
    }).catch((nextError) => {
      if (!cancelled) setError(message(nextError))
    })
    void api.aiResearchResult(dataset.id).then((next) => {
      if (!cancelled) acceptResult(next)
    }).catch(() => undefined)
    return () => { cancelled = true }
  }, [acceptResult, dataset.id])

  const analyze = async () => {
    setBusy(true)
    setError('')
    try {
      acceptResult(await api.analyzeWithAi(dataset.id))
      setStatus(await api.aiResearchStatus())
    } catch (nextError) {
      setError(message(nextError))
    } finally {
      setBusy(false)
    }
  }

  const probabilities = useMemo(() => Object.entries(result?.probabilities || {})
    .sort((left, right) => right[1] - left[1]), [result])
  const sourceRegions = useMemo(() => {
    const transform = result?.source_coordinate_transform
    return (result?.suspected_regions || []).map((region) => !transform?.applied ? region : ({
      ...region,
      x: Math.round(transform.origin_x + region.x * transform.scale_x),
      y: Math.round(transform.origin_y + region.y * transform.scale_y),
      width: Math.round(region.width * transform.scale_x),
      height: Math.round(region.height * transform.scale_y),
    }))
  }, [result])

  return (
    <main className="forge-ai-workspace">
      <header className="forge-ai-header">
        <button type="button" className="forge-ai-back" onClick={onClose}>
          <ArrowLeft /> Back to Forge
        </button>
        <div>
          <span><Flask /> PathLab AI research bench</span>
          <h1>{dataset.displayName}</h1>
        </div>
        <button
          type="button"
          className="forge-ai-run"
          disabled={busy || !status?.available}
          onClick={() => void analyze()}
        >
          <Play weight="fill" /> {busy ? 'Analyzing WSI…' : result ? 'Analyze again' : 'Analyze WSI'}
        </button>
      </header>

      <div className="forge-ai-warning" role="note">
        <Warning weight="fill" />
        <span><strong>Research output only.</strong> Highlighted boxes are AI-suspected evidence—not confirmed disease or diagnostic boundaries.</span>
      </div>

      <section className="forge-ai-layout">
        <div className="forge-ai-viewer-panel">
          <SlideViewer
            tileSource={tileSource}
            sourceWidth={dataset.width || 1}
            sourceHeight={dataset.height || 1}
            cropX={cropX}
            cropY={cropY}
            downsample={downsample}
            evidenceRegions={sourceRegions}
            selectedEvidenceRegionId={selectedRegionId}
            onEvidenceRegionSelect={setSelectedRegionId}
            onReady={setViewer}
          />
          <div className="forge-ai-viewer-tools" aria-label="AI viewer controls">
            <button type="button" aria-label="Zoom out" onClick={() => viewer?.viewport.zoomBy(.67)}><MagnifyingGlassMinus /></button>
            <button type="button" aria-label="Show whole slide" onClick={() => viewer?.viewport.goHome()}><Crosshair /></button>
            <button type="button" aria-label="Zoom in" onClick={() => viewer?.viewport.zoomBy(1.5)}><MagnifyingGlassPlus /></button>
          </div>
          {busy ? (
            <div className="forge-ai-analyzing" role="status" aria-live="polite">
              <Brain /> Reading representative tissue tiles and locating evidence…
            </div>
          ) : null}
        </div>

        <aside className="forge-ai-results" aria-label="AI research result">
          <div className="forge-ai-result-status" aria-live="polite">
            {error ? <p className="forge-ai-error">{error}</p> : null}
            {!result ? (
              <div className="forge-ai-empty-result">
                <Brain />
                <strong>{status?.available ? 'Ready to inspect this WSI' : 'AI runtime is not configured'}</strong>
                <p>{status?.detail || 'Checking the local research model…'}</p>
              </div>
            ) : (
              <>
                <span className="forge-ai-kicker">Predicted class</span>
                <div className="forge-ai-prediction">
                  <strong>{result.label}</strong>
                  <span>{result.coarse_group}</span>
                  <b>{percent(result.confidence)}</b>
                </div>
                <p className={result.needs_review ? 'review-required' : ''}>
                  {result.needs_review ? 'Confidence review required' : 'Above the model review threshold'}
                </p>
              </>
            )}
          </div>

          {result ? (
            <>
              <section className="forge-ai-regions">
                <div><span className="forge-ai-kicker">Automatically selected evidence</span><small>{sourceRegions.length} distinct region{sourceRegions.length === 1 ? '' : 's'}</small></div>
                {sourceRegions.length ? sourceRegions.map((region) => (
                  <button
                    type="button"
                    key={region.id}
                    className={region.id === selectedRegionId ? 'selected' : ''}
                    onClick={() => setSelectedRegionId(region.id)}
                    aria-pressed={region.id === selectedRegionId}
                  >
                    <b>{region.rank}</b>
                    <span><strong>Evidence region {region.rank}</strong><small>x {region.x.toLocaleString()} · y {region.y.toLocaleString()} · {region.tile_count} tile{region.tile_count === 1 ? '' : 's'}</small></span>
                    <em>{percent(region.relative_score)}</em>
                  </button>
                )) : <p>No positive class-evidence region was found. Review the whole slide manually.</p>}
              </section>

              <section className="forge-ai-probabilities">
                <span className="forge-ai-kicker">Class comparison</span>
                {probabilities.map(([label, probability]) => (
                  <div key={label}>
                    <span>{label}</span>
                    <i><b style={{ width: percent(probability) }} /></i>
                    <em>{percent(probability)}</em>
                  </div>
                ))}
              </section>

              <footer className="forge-ai-provenance">
                <span>{result.model}</span>
                <span>{result.tile_count} sampled tiles · {result.runtime_seconds.toFixed(1)} s</span>
              </footer>
            </>
          ) : null}
        </aside>
      </section>
    </main>
  )
}

function percent(value: number) {
  return `${Math.round(value * 100)}%`
}

function message(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
