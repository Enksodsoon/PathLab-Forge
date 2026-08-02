export interface Dataset {
  id: string
  displayName: string
  sourceBytes: number
  format: 'OME_TIFF' | 'VSI' | 'SVS'
  status: string
  detail: string
  outputPath: string
  sha256: string
  selectedSeries: number
  width: number
  height: number
  downsample: number
  estimatedOutputBytes: number
  projectedFileBytes: number
  projectedFileLowerBytes: number
  projectedFileUpperBytes: number
  cropX: number
  cropY: number
  cropWidth: number
  cropHeight: number
  sourceFingerprint: string
  configurationRevision: string
  currentArtifactRevision: string
  approvedArtifactRevision: string
  workspaceRevision?: number
  verificationState?: 'PENDING' | 'VERIFIED' | 'CHANGED' | 'FAILED'
  stage?: string
  completedUnits?: number
  totalUnits?: number
  elapsedMs?: number
  estimatedRemainingMs?: number
  unitsPerSecond?: number
  peakWorkingSetBytes?: number
  resourceProfile?: string
  cacheHitReason?: string
}

export interface SeriesInfo {
  index: number
  name: string
  width: number
  height: number
  channels: number
  sizeZ: number
  sizeT: number
  pixelType: string
  physicalSizeX: number
  physicalSizeY: number
  physicalUnit: string
  resolutionCount: number
  rgbPlane: boolean
}

export interface ArtifactRevision {
  id: string
  name?: string
  status: 'CONVERTING' | 'READY' | 'APPROVED' | 'FAILED'
  format?: 'LEGACY_OME' | 'PREPARED_DZI_V2'
  createdAt: number
  outputWidth: number
  outputHeight: number
  omePath: string
  packagePath: string
  omeSha256: string
  omeBytes: number
  dziBytes: number
  packageBytes: number
  jpegQuality: number
  minimumWindowedSsim: number
  maximumRoiMeanDeltaE00: number
  minimumEdgeDetailRetention: number
  encoderProfile: string
  sizeReferenceKind?: string
  series?: number
  cropX?: number
  cropY?: number
  cropWidth?: number
  cropHeight?: number
  downsample?: number
  packageSha256: string
  failure: string
}

export interface OutputEstimate {
  outputWidth: number
  outputHeight: number
  fileBytes: number
  fileLowerBytes: number
  fileUpperBytes: number
  workspaceBytes: number
}

export interface ViewerConnection {
  connected: boolean
  viewerUrl: string
  deviceName: string
  scopes: string[]
}

export interface ViewerPairing {
  userCode: string
  verificationUrl: string
  expiresAt: string
}

export interface ViewerUpload {
  state: 'IDLE' | 'UPLOADING' | 'READY_PRIVATE' | 'FAILED'
  artifactRevisionId: string
  uploadedBytes: number
  totalBytes: number
  viewerSlideId: string
  uploadMode: 'OME_DYNAMIC' | 'PREPARED_V2' | ''
  detail: string
}

export interface AnnotationRecord {
  id: string
  type: string
  geometry: string
  label: string
  color: string
  createdAt: number
}

export interface PivotManifestSummary {
  status: 'NOT_BUILT' | 'READY'
  detail?: string
  schema?: string
  algorithmVersion?: string
  manifestId?: string
  totalTasks?: number
  generationMs?: number
  inspectedCandidates?: number
  rejectedBlank?: number
  rejectedMissing?: number
  createdAt?: number
  nonDiagnostic?: boolean
}

export interface PivotTask {
  id: string
  queryUrl: string
  difficulty: number
  difficultyLabel: 'Foundation' | 'Moderate' | 'Challenge'
  scaleGap: number
  index: number
  total: number
}

export interface PivotAttempt {
  taskId: string
  normalizedError: number
  rating: 'MATCH' | 'CLOSE' | 'MISSED'
  elapsedMs: number
  confidence: number
}

export interface PivotSession {
  id: string
  state: 'ACTIVE' | 'COMPLETED'
  startedAt: number
  updatedAt: number
  completedTasks: number
  skippedTasks: number
  hintsUsed: number
  totalTasks: number
  currentTask: PivotTask | null
  recentAttempts: PivotAttempt[]
}

export interface PivotScore {
  normalizedError: number
  distancePixels: number
  rating: 'MATCH' | 'CLOSE' | 'MISSED'
  target: { x: number; y: number; width: number; height: number }
  session: PivotSession
}

export interface StudyPackRecord {
  packKey: string
  version: number
  title: string
  checksum: string
  masteryEligible: boolean
}

export interface ViewerStudyPack {
  id: string
  packKey: string
  version: number
  checksum: string
  masteryEligible: boolean
  status: string
}

export interface ImportedStudyTask {
  id: string
  prompt: string
  answerKey: string
  keyOrigin: 'imported' | 'faculty-approved'
}

export interface AiResearchStatus {
  available: boolean
  busy: boolean
  detail: string
}

export interface AiEvidenceRegion {
  id: string
  rank: number
  x: number
  y: number
  width: number
  height: number
  tile_count: number
  score: number
  relative_score: number
  maximum_attention: number
  maximum_contribution: number
  auto_selected: boolean
}

export interface AiResearchResult {
  schema_version: number
  label: string
  coarse_group: string
  confidence: number
  needs_review: boolean
  review: { required: boolean; confidence_threshold: number; reason: string }
  probabilities: Record<string, number>
  tile_count: number
  source_tile_pixels: number
  suspected_regions: AiEvidenceRegion[]
  auto_selected_region_id: string | null
  evidence_interpretation: string
  region_interpretation: string
  model: string
  intended_use: string
  runtime_seconds: number
  source_coordinate_transform?: {
    origin_x: number
    origin_y: number
    scale_x: number
    scale_y: number
    applied: boolean
  }
}

let csrf = ''
let datasetEtag = ''
let datasetCache: Dataset[] | undefined

export async function bootstrap() {
  const response = await fetch('/api/session', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('Forge session is unavailable')
  csrf = response.headers.get('X-Forge-CSRF') || ''
  return Promise.all([datasets(), capabilities()])
}

export async function datasets(): Promise<Dataset[]> {
  const headers = new Headers()
  if (datasetEtag) headers.set('If-None-Match', datasetEtag)
  const response = await fetch('/api/datasets', {
    headers,
    credentials: 'same-origin',
  })
  if (response.status === 304 && datasetCache) return datasetCache
  const body = await response.json() as { datasets?: Dataset[]; detail?: string; error?: string }
  if (!response.ok || !body.datasets) {
    throw new Error(body.detail || body.error || `Request failed (${response.status})`)
  }
  datasetEtag = response.headers.get('ETag') || ''
  datasetCache = body.datasets
  return body.datasets
}

export async function capabilities() {
  return request<{
    conversionRuntime: string
    derivativeRuntime: string
    vsiConversion: boolean
    dziGeneration: boolean
    downsamples: number[]
    activeConversions?: number
    queuedConversions?: number
    maximumConcurrentConversions?: number
    projectFolderImport?: boolean
  }>('/api/capabilities')
}

export async function importProjectFolder(path?: string) {
  const query = path?.trim() ? `?path=${encodeURIComponent(path.trim())}` : ''
  return request<{ datasets: Dataset[]; project?: { root: string; imported: number; failed: string } }>(
    `/api/v2/desktop/projects/import-folder${query}`,
    { method: 'POST' },
  )
}

export async function chooseDatasets() {
  return request<{ datasets: Dataset[] }>('/api/datasets/select', { method: 'POST' })
}

export async function importDataset(path: string) {
  return request<{ datasets: Dataset[] }>(
    `/api/datasets/import?path=${encodeURIComponent(path)}`,
    { method: 'POST' },
  )
}

export async function deleteDataset(id: string) {
  return request<void>(`/api/datasets/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function inspectDataset(id: string) {
  const body = await request<{ series: SeriesInfo[] }>(
    `/api/datasets/${encodeURIComponent(id)}/inspect`,
    { method: 'POST' },
  )
  return body.series
}

export async function series(id: string) {
  const body = await request<{ series: SeriesInfo[] }>(
    `/api/datasets/${encodeURIComponent(id)}/series`,
  )
  return body.series
}

export async function configure(
  id: string,
  values: {
    series: number
    downsample: number
    x: number
    y: number
    width: number
    height: number
  },
) {
  const query = new URLSearchParams(Object.entries(values).map(([key, value]) => [key, String(value)]))
  return request<Dataset>(
    `/api/datasets/${encodeURIComponent(id)}/series?${query}`,
    { method: 'POST' },
  )
}

export async function estimate(
  id: string,
  values: { downsample: number; width: number; height: number },
  signal?: AbortSignal,
) {
  const query = new URLSearchParams(Object.entries(values).map(([key, value]) => [key, String(value)]))
  return request<OutputEstimate>(
    `/api/datasets/${encodeURIComponent(id)}/estimate?${query}`,
    { signal },
  )
}

export async function convert(id: string) {
  return request<Dataset>(`/api/datasets/${encodeURIComponent(id)}/convert`, { method: 'POST' })
}

export async function cancel(id: string) {
  return request<Dataset>(`/api/datasets/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
}

export async function artifacts(id: string) {
  return request<{
    currentRevision: string
    approvedRevision: string
    revisions: ArtifactRevision[]
  }>(`/api/datasets/${encodeURIComponent(id)}/artifacts`)
}

export async function approve(id: string, revision: string) {
  return request<Dataset>(
    `/api/datasets/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(revision)}/approve`,
    { method: 'POST' },
  )
}

export async function renameArtifact(id: string, revision: string, name: string) {
  return request<ArtifactRevision>(
    `/api/datasets/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(revision)}/rename?name=${encodeURIComponent(name)}`,
    { method: 'POST' },
  )
}

export async function deleteArtifact(id: string, revision: string) {
  return request<Dataset>(
    `/api/datasets/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(revision)}`,
    { method: 'DELETE' },
  )
}

export function artifactPackageUrl(id: string, revision: string) {
  return `/api/datasets/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(revision)}/package`
}

export function artifactDziUrl(id: string, revision: string) {
  return `/api/datasets/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(revision)}/derivative/slide.dzi`
}

export async function getViewerConnection() {
  return request<ViewerConnection>('/api/viewer/connection')
}

export async function startViewerPairing(viewerUrl: string) {
  return request<ViewerPairing>(
    `/api/viewer/pairing/start?viewerUrl=${encodeURIComponent(viewerUrl)}`,
    { method: 'POST' },
  )
}

export async function exchangeViewerPairing() {
  return request<ViewerConnection>('/api/viewer/pairing/exchange', { method: 'POST' })
}

export async function uploadApprovedArtifact(id: string) {
  return request<ViewerUpload>(
    `/api/datasets/${encodeURIComponent(id)}/upload`,
    { method: 'POST' },
  )
}

export async function getViewerUpload() {
  return request<ViewerUpload>('/api/viewer/upload')
}

export async function revokeViewerConnection() {
  return request<void>('/api/viewer/connection/revoke', { method: 'POST' })
}

export async function annotations(id: string) {
  const body = await request<{ annotations: AnnotationRecord[] }>(
    `/api/datasets/${encodeURIComponent(id)}/annotations`,
  )
  return body.annotations
}

export async function aiResearchStatus() {
  return request<AiResearchStatus>('/api/v2/desktop/ai-research/status')
}

export async function aiResearchResult(id: string) {
  return request<AiResearchResult>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/ai-research/result`,
  )
}

export async function analyzeWithAi(id: string) {
  return request<AiResearchResult>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/ai-research/analyze`,
    { method: 'POST' },
  )
}

export async function createAnnotation(
  id: string,
  values: { type: string; geometry: string; label?: string; color?: string },
) {
  const query = new URLSearchParams({
    type: values.type.replaceAll('-', '_'),
    geometry: values.geometry,
    label: values.label || '',
    color: values.color || '#f3b33d',
  })
  return request<AnnotationRecord>(
    `/api/datasets/${encodeURIComponent(id)}/annotations?${query}`,
    { method: 'POST' },
  )
}

export async function deleteAnnotation(id: string, annotationId: string) {
  return request<void>(
    `/api/datasets/${encodeURIComponent(id)}/annotations/${encodeURIComponent(annotationId)}`,
    { method: 'DELETE' },
  )
}

export async function pivotStatus(id: string) {
  return request<PivotManifestSummary>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/pivot`,
  )
}

export async function compilePivot(id: string) {
  return request<PivotManifestSummary>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/pivot/compile`,
    { method: 'POST' },
  )
}

export async function pivotSession(id: string) {
  return request<PivotSession>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/pivot/session`,
  )
}

export async function startPivotSession(id: string) {
  return request<PivotSession>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/pivot/session`,
    { method: 'POST' },
  )
}

export async function submitPivot(
  id: string,
  values: {
    x: number
    y: number
    elapsedMs: number
    panDistance: number
    zoomReversals: number
    confidence: number
  },
) {
  const query = new URLSearchParams(Object.entries(values).map(([key, value]) => [key, String(value)]))
  return request<PivotScore>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/pivot/session/submit?${query}`,
    { method: 'POST' },
  )
}

export async function hintPivot(id: string) {
  return request<{ text: string; session: PivotSession }>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/pivot/session/hint`,
    { method: 'POST' },
  )
}

export async function skipPivot(id: string) {
  return request<PivotSession>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/pivot/session/skip`,
    { method: 'POST' },
  )
}

export async function endPivot(id: string) {
  return request<PivotSession>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/pivot/session/end`,
    { method: 'POST' },
  )
}

export async function studyPacks() {
  return request<{ items: StudyPackRecord[] }>('/api/v2/desktop/adapt/packs')
}

export async function saveStudyPack(body: string) {
  return request<StudyPackRecord>('/api/v2/desktop/adapt/packs', { method: 'POST', body })
}

export async function publishStudyPack(checksum: string) {
  return request<ViewerStudyPack>(
    `/api/v2/desktop/adapt/packs/${encodeURIComponent(checksum)}/publish`,
    { method: 'POST' },
  )
}

export async function exportPivotStudyPack(values: {
  datasetId: string
  packKey: string
  version: number
  title: string
  courseId: string
  viewerSlideId: string
  author: string
  license: string
  revision: string
  facultyApproved: boolean
}) {
  return request<StudyPackRecord>('/api/v2/desktop/adapt/packs/pivot', {
    method: 'POST', body: JSON.stringify(values),
  })
}

export async function importAnkiPackage(file: File) {
  const headers = new Headers({
    'X-Forge-CSRF': csrf,
    Origin: window.location.origin,
    'Content-Type': 'application/octet-stream',
  })
  const response = await fetch('/api/v2/desktop/adapt/imports/anki', {
    method: 'POST', headers, body: file, credentials: 'same-origin',
  })
  const body = await response.json() as { items?: ImportedStudyTask[]; detail?: string; error?: string }
  if (!response.ok || !body.items) {
    throw new Error(body.detail || body.error || `Request failed (${response.status})`)
  }
  return body.items
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.method && init.method !== 'GET') {
    headers.set('X-Forge-CSRF', csrf)
    headers.set('Origin', window.location.origin)
  }
  const response = await fetch(path, {
    ...init,
    headers,
    credentials: 'same-origin',
  })
  const body = response.status === 204 ? undefined : await response.json()
  if (!response.ok) {
    throw new Error(body?.detail || body?.error || `Request failed (${response.status})`)
  }
  return body as T
}
