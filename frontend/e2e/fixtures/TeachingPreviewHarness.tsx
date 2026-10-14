import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { TeachingSlidePreview } from '../../src/TeachingSlidePreview'
import type { TeachingAssociation } from '../../src/teachingAssociations'
import type OpenSeadragon from 'openseadragon'
import './teaching-preview.css'

const association: TeachingAssociation = {
  referenceId: 'synthetic:landmarks',
  viewerSlideId: '',
  datasetId: 'synthetic-dataset',
  artifactRevision: 'synthetic-revision',
  packageSha256: 'a'.repeat(64),
  outputWidth: 640,
  outputHeight: 320,
}

declare global { interface Window { __teachingViewer?: OpenSeadragon.Viewer | null } }

function TeachingPreviewHarness() {
  const [location, setLocation] = useState<{ x: number; y: number }>()
  const [ready, setReady] = useState(false)
  return <main>
    <h1>Non-square teaching preview fixture</h1>
    <output data-testid="teaching-ready">{ready ? 'Image ready' : 'Image loading'}</output>
    <output data-testid="teaching-location">{location ? `${location.x.toFixed(5)},${location.y.toFixed(5)}` : 'No location selected'}</output>
    <TeachingSlidePreview association={association} slideId={association.referenceId}
      previewChecksum="synthetic-preview-checksum" onPixelsLoaded={(pixels) => setReady(Boolean(pixels))}
      onLocation={(x, y) => setLocation({ x, y })} selectedLocation={location}
      onViewerReady={(viewer) => { window.__teachingViewer = viewer }} />
  </main>
}

createRoot(document.getElementById('fixture')!).render(<TeachingPreviewHarness />)
