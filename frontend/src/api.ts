import type { StudyDraftRecord } from './StudyAuthoring'
import type { DeterministicRun, DeterministicRequest, DeterministicReview } from './DeterministicTools'
import type { BatchSummary, BatchReport } from './BatchReports'
import type { StudySlide } from './StudyAuthoring'

export const teachingSlides = async (): Promise<StudySlide[]> => {
  const slides = await request<Array<{ id: string; displayName: string; sha256: string }>>('/api/study/viewer/slides')
  if (!Array.isArray(slides) || slides.some((slide) => !slide.id || !slide.displayName || !/^[a-f0-9]{64}$/.test(slide.sha256))) throw new Error('Viewer teaching slide identities are invalid')
  return slides.map((slide) => ({ viewerSlideId: slide.id, displayName: slide.displayName, sha256: slide.sha256 }))
}
export const publishStudy = (id: string, revision: number, checksum: string) => request<{ id: string; checksum: string }>(`/api/study/drafts/${encodeURIComponent(id)}/publish`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision, checksum }) })
export const generateTeachingArtifact = (id: string) => request<Dataset>(`/api/datasets/${encodeURIComponent(id)}/teaching`, { method: 'POST' })

export const batches = () => request<BatchSummary[]>('/api/batches?limit=50')
export const createBatch = (datasetIds: string[]) => request<BatchSummary>('/api/batches', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ datasetIds }) })
export const batchReport = (id: string) => request<BatchReport>(`/api/batches/${encodeURIComponent(id)}/report`)
export const retryBatchItem = (id: string, datasetId: string) => request<BatchSummary>(`/api/batches/${encodeURIComponent(id)}/retry`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ datasetId }) })
export const cancelBatch = (id: string) => request<BatchSummary>(`/api/batches/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
export const exportBatch = (batchId: string, format: 'csv' | 'json', destination: string) => request<ExportState>('/api/exports', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind: 'batch', batchId, format, destination }) })

export interface ExportState { id: string; status: string; completedBytes: number; totalBytes: number; destination: string; detail: string }
export const exportState = () => request<ExportState>('/api/exports')
export const cancelExport = (expectedId: string) => request<ExportState>(`/api/exports/cancel?id=${encodeURIComponent(expectedId)}`, { method: 'POST' })
export const exportArtifact = (datasetId: string, revisionId: string, kind: 'ome' | 'package', destination: string) =>
  request<ExportState>('/api/exports', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ datasetId, revisionId, kind, destination }) })
export const exportResult = (kind: 'analysis' | 'measurements', datasetId: string, runId: string, destination: string) =>
  request<ExportState>('/api/exports', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ datasetId, runId, kind, destination }) })
export const analysisRuns = (datasetId: string) => request<DeterministicRun[]>(`/api/analysis/runs?datasetId=${encodeURIComponent(datasetId)}`)
export const analysisRun = (id: string) => request<DeterministicRun>(`/api/analysis/runs/${encodeURIComponent(id)}`)
export const analysisHistory = (datasetId: string, offset: number) => request<{ runs: DeterministicRun[]; hasMore: boolean; nextOffset: number }>(`/api/analysis/runs?datasetId=${encodeURIComponent(datasetId)}&page=true&limit=100&offset=${offset}`)
export const persistTma = (id: string, reviewRevision: number) => request<AnnotationRecord[]>(`/api/analysis/runs/${encodeURIComponent(id)}/cores`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reviewRevision }) })
export const analyzeTmaCore = (id: string, reviewRevision: number, coreId: string, tool: string, configuration: Record<string, number>) => request<DeterministicRun>(`/api/analysis/runs/${encodeURIComponent(id)}/core-analysis`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reviewRevision, coreId, tool, configuration }) })
export const submitAnalysis = (value: DeterministicRequest) => request<DeterministicRun>('/api/analysis/runs', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) })
export const cancelAnalysis = (id: string) => request<DeterministicRun>(`/api/analysis/runs/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
export const analysisReview = (id: string) => request<DeterministicReview>(`/api/analysis/runs/${encodeURIComponent(id)}/review`)
export const saveAnalysisReview = (id: string, review: DeterministicReview) => request<DeterministicReview>(`/api/analysis/runs/${encodeURIComponent(id)}/review`, {
  method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(review) })

export interface Dataset {
  id: string
  displayName: string
  sourceBytes: number
  format: string
  readerEngine?: string
  readerId?: string
  formatName?: string
  runtimeFingerprint?: string
  viewDefinitionJson?: string
  viewRevision?: string
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

export type AxisMode = 'SLICE' | 'MIN' | 'MAX' | 'MEAN'
export interface ViewDefinition {
  series: number
  z: { mode: AxisMode; start: number; end: number }
  t: { mode: AxisMode; start: number; end: number }
  channels: Array<{
    channel: number; enabled: boolean; color: string; minimum: number; maximum: number
  }>
  profile: 'PATHOLOGY_STANDARD' | 'DISPLAY_COMPOSITE'
}

export interface ImportDiagnostic {
  code: 'UNSUPPORTED' | 'CORRUPT' | 'ENCRYPTED' | 'MISSING_COMPANION'
    | 'CODEC_UNAVAILABLE' | 'PROBE_TIMEOUT' | 'RESOURCE_LIMIT'
  detail: string
  repairable: boolean
  paths: string[]
}

export interface FormatCatalog {
  policy: 'BEST_EFFORT'
  runtimeVersion: string
  runtimeFingerprint: string
  formats: Array<{
    engine: string
    readerId: string
    displayName: string
    extensions: string[]
    multidimensional: boolean
    nativePyramid: boolean
    groupedFiles: boolean
    randomRegions: boolean
  }>
}

export interface ArtifactRevision {
  configurationRevision?: string
  id: string
  name?: string
  status: 'CONVERTING' | 'READY' | 'APPROVED' | 'FAILED'
  format?: 'LEGACY_OME' | 'OME_DYNAMIC_V1' | 'PREPARED_DZI_V2'
  createdAt: number
  outputWidth: number
  outputHeight: number
  omePath: string
  packagePath: string
  omeSha256: string
  omeProfile?: string
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
  connectionRevision?: string
  conversionMode?: 'OME_DYNAMIC_V1' | 'PREPARED_DZI_V2'
}

export interface ViewerPairing {
  userCode: string
  verificationUrl: string
  verificationUrlComplete: string
  pollIntervalSeconds: number
  expiresAt: string
}

export interface ViewerUpload {
  state: 'IDLE' | 'UPLOADING' | 'VERIFYING_OME' | 'IMAGE_READY'
    | 'SYNCING_RESULTS' | 'COMPLETE' | 'RETRYING' | 'PAUSED' | 'FAILED' | 'CANCELLED'
  artifactRevisionId: string
  uploadedBytes: number
  totalBytes: number
  viewerSlideId: string
  viewerSlideSha256: string
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
  parentId: string
  classification: string
  updatedAt: number
  revision: number
  series?: number
  z?: number
  t?: number
  viewRevision?: string
}

export interface ViewerRemoteItem {
  id: string
  displayName: string
  folderId: string
  state: string
  contentBytes: number
  width: number
  height: number
  thumbnailUrl: string
  tileSourceUrl: string
  offlineBytes: number
  offlineComplete: boolean
  downloadState?: 'NONE' | 'DOWNLOADING' | 'VERIFYING' | 'READY' | 'FAILED' | 'CANCELLED'
  downloadDetail?: string
  visibility?: 'private' | 'published'
  annotationRevision?: number
  metadataRevision?: number
  updatedAt?: string
  metadata?: Record<string, unknown>
}

export interface ViewerRemoteLibrary {
  items: ViewerRemoteItem[]
  folders: Array<{ id: string; name: string; parentId: string }>
  conflicts: Array<{ slideId: string; field: string; localValue?: unknown; remoteValue?: unknown; baseRevision?: number; remoteRevision?: number }>
}

export interface FeaturePack {
  activeVersion?: string
  installedVersions?: string[]
  platforms?: string[]
  minimumCoreVersion?: string
  licenseReviewStatus?: string
  id: string
  version: string
  name: string
  kind: string
  state: 'NOT_PUBLISHED' | 'AVAILABLE' | 'INSTALLED' | 'DISABLED' | 'UNAVAILABLE' | 'INCOMPATIBLE'
  downloadBytes: number
  installedBytes: number
  minimumMemoryBytes: number
  minimumProcessors: number
  pretrained: boolean
  trainingOnly: boolean
  license: string
  detail: string
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
    queuePaused?: boolean
    usableBytes?: number
    effectiveCapacityBytes?: number
    maximumConcurrentConversions?: number
    projectFolderImport?: boolean
  }>('/api/capabilities')
}

export async function setQueuePaused(paused: boolean) {
  return request<{ paused: boolean; active: number; queued: number }>(
    `/api/queue?paused=${paused}`, { method: 'POST' },
  )
}

export async function importProjectFolder(path?: string) {
  const query = path?.trim() ? `?path=${encodeURIComponent(path.trim())}` : ''
  return request<{ datasets: Dataset[]; project?: { root: string; imported: number; failed: string } }>(
    `/api/v2/desktop/projects/import-folder${query}`,
    { method: 'POST' },
  )
}

export interface LocalFileListing {
  path: string
  parent: string | null
  locations: Array<{ name: string; path: string }>
  entries: Array<{ name: string; path: string; directory: boolean; bytes: number }>
  truncated: boolean
}

export async function browseLocalFiles(path?: string) {
  const query = path?.trim() ? `?path=${encodeURIComponent(path.trim())}` : ''
  return request<LocalFileListing>(`/api/local-files${query}`)
}

export async function importDataset(path: string) {
  const report = await importDatasets([path])
  if (!report.datasets.length && report.diagnostics.length) {
    throw new Error(`${report.diagnostics[0].code}: ${report.diagnostics[0].detail}`)
  }
  datasetEtag = ''
  return { datasets: await datasets() }
}

export async function formats() {
  return request<FormatCatalog>('/api/v2/desktop/formats')
}

export async function importDatasets(paths: string[]) {
  return request<{ datasets: Dataset[]; diagnostics: ImportDiagnostic[] }>(
    '/api/v2/desktop/imports', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ paths }),
    },
  )
}

export async function images(id: string) {
  return request<{ series: SeriesInfo[]; viewDefinition: ViewDefinition | null }>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/images`,
  )
}

export async function updateView(id: string, view: ViewDefinition) {
  return request<Dataset & { viewRevision: string }>(
    `/api/v2/desktop/datasets/${encodeURIComponent(id)}/view`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(view),
    },
  )
}

export function viewDziUrl(id: string, revision: string) {
  return `/api/v2/desktop/datasets/${encodeURIComponent(id)}/views/${encodeURIComponent(revision)}/slide.dzi`
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

export function artifactOmePreviewUrl(id: string, revision: string) {
  return `/api/datasets/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(revision)}/ome-preview/slide.dzi`
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

export async function cancelViewerUpload() {
  return request<ViewerUpload>('/api/viewer/upload/cancel', { method: 'POST' })
}

export async function revokeViewerConnection() {
  return request<void>('/api/viewer/connection/revoke', { method: 'POST' })
}

export async function viewerLibrary() {
  return request<ViewerRemoteLibrary>('/api/viewer/library')
}

export async function syncViewerLibrary() {
  return request<ViewerRemoteLibrary>('/api/viewer/sync', { method: 'POST' })
}

export async function keepViewerSlideOffline(id: string) {
  return request<{ state: string }>(`/api/viewer/slides/${encodeURIComponent(id)}/offline`, { method: 'POST' })
}

export async function removeViewerSlideOffline(id: string) {
  return request<void>(`/api/viewer/slides/${encodeURIComponent(id)}/offline`, { method: 'DELETE' })
}

export async function updateViewerSlideMetadata(id: string, values: { displayName?: string; folderId?: string }) {
  const query = new URLSearchParams()
  if (values.displayName !== undefined) query.set('displayName', values.displayName)
  if (values.folderId !== undefined) query.set('folderId', values.folderId)
  return request<{ id: string; displayName: string }>(
    `/api/viewer/slides/${encodeURIComponent(id)}/metadata?${query}`, { method: 'POST' },
  )
}

export async function viewerSlideAnnotations(id: string) {
  return request<Record<string, unknown>>(`/api/viewer/slides/${encodeURIComponent(id)}/annotations`)
}

export async function mutateViewerSlideAnnotations(id: string, payload: Record<string, unknown>) {
  return request<Record<string, unknown>>(
    `/api/viewer/slides/${encodeURIComponent(id)}/annotations?payload=${encodeURIComponent(JSON.stringify(payload))}`,
    { method: 'POST' },
  )
}

export async function resolveViewerConflict(id: string, field: string, resolution: 'local' | 'viewer') {
  return request<void>(`/api/viewer/conflicts/${encodeURIComponent(id)}/resolve?field=${encodeURIComponent(field)}&resolution=${resolution}`, { method: 'POST' })
}

export async function annotations(id: string) {
  const body = await request<{ annotations: AnnotationRecord[] }>(
    `/api/datasets/${encodeURIComponent(id)}/annotations`,
  )
  return body.annotations
}

export async function createAnnotation(
  id: string,
  values: { type: string; geometry: string; label?: string; color?: string; configurationRevision?: string },
) {
  const query = new URLSearchParams({
    type: values.type.replaceAll('-', '_'),
    geometry: values.geometry,
    label: values.label || '',
    color: values.color || '#f3b33d',
    configurationRevision: values.configurationRevision || '',
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

export async function updateAnnotation(id: string, annotation: AnnotationRecord,
  values: { geometry?: string; label?: string; color?: string }) {
  const query = new URLSearchParams({ geometry: values.geometry ?? annotation.geometry,
    label: values.label ?? annotation.label, color: values.color ?? annotation.color,
    revision: String(annotation.revision) })
  return request<AnnotationRecord>(
    `/api/datasets/${encodeURIComponent(id)}/annotations/${encodeURIComponent(annotation.id)}?${query}`,
    { method: 'PATCH' },
  )
}

export async function features(refresh = false) {
  return request<{ features: FeaturePack[] }>(`/api/features${refresh ? '?refresh=true' : ''}`)
}

export async function installFeature(id: string) {
  return request<FeaturePack>(`/api/features/${encodeURIComponent(id)}/install`, { method: 'POST' })
}

export async function disableFeature(id: string) {
  return request<void>(`/api/features/${encodeURIComponent(id)}/disable`, { method: 'POST' })
}

export type FeatureAction = 'install' | 'disable' | 'uninstall' | 'enable' | 'activate' | 'rollback' | 'cancel'
export interface FeatureProgress { id: string; phase: string; completedBytes: number; totalBytes: number; detail: string }
export const featureProgress = () => request<FeatureProgress>('/api/features/progress')
export const featureAction = (id: string, action: FeatureAction, version = '') =>
  request<void>(`/api/features/${encodeURIComponent(id)}/${action}?version=${encodeURIComponent(version)}`, { method: 'POST' })

export async function uninstallFeature(id: string) {
  return request<void>(`/api/features/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function annotationMeasurements(id: string, annotationId: string) {
  return request<{ annotationId: string; units: 'pixels' | 'pixels-and-micrometres'; values: Record<string, number> }>(
    `/api/datasets/${encodeURIComponent(id)}/annotations/${encodeURIComponent(annotationId)}/measurements`,
  )
}

async function request<T>(path: string, init: RequestInit = {}, sessionRetry = true): Promise<T> {
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
  if (response.status === 403 && sessionRetry && init.method && init.method !== 'GET'
      && body?.error === 'forbidden') {
    const session = await fetch('/api/session', { credentials: 'same-origin' })
    if (session.ok) {
      csrf = session.headers.get('X-Forge-CSRF') || ''
      return request<T>(path, init, false)
    }
  }
  if (!response.ok) {
    throw new Error(body?.detail || body?.error || `Request failed (${response.status})`)
  }
  return body as T
}

const studyPath = (id: string) => `/api/study/drafts/${encodeURIComponent(id)}`
const studyWrite = <T,>(path: string, value: unknown, method = 'POST') => request<T>(path, {
  method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) })
export const studyDrafts = () => request<StudyDraftRecord[]>('/api/study/drafts')
export const studyDraft = (id: string) => request<StudyDraftRecord>(studyPath(id))
export const createStudyDraft = (name: string) => studyWrite<StudyDraftRecord>('/api/study/drafts', { name })
export const saveStudyDraft = (draft: StudyDraftRecord, revision: number) => studyWrite<StudyDraftRecord>(studyPath(draft.id), { name: draft.name, revision, definition: draft.definition, associations: draft.associations }, 'PUT')
export const duplicateStudyDraft = (id: string, name: string, nextVersion: boolean) => studyWrite<StudyDraftRecord>(`${studyPath(id)}/duplicate`, { name, nextVersion })
export const studyHistory = (id: string) => request<StudyDraftRecord[]>(`${studyPath(id)}/history`)
export const recoverStudyDraft = (id: string, historicalRevision: number, revision: number) => studyWrite<StudyDraftRecord>(`${studyPath(id)}/recover`, { historicalRevision, revision })
export const previewStudyDraft = (id: string, revision: number) => studyWrite<StudyDraftRecord>(`${studyPath(id)}/preview`, { revision })
export const reviewStudyTask = (id: string, revision: number, checksum: string, taskId: string) => studyWrite<StudyDraftRecord>(`${studyPath(id)}/review`, { revision, checksum, taskId })
export const approveStudyDraft = (id: string, revision: number, checksum: string) => studyWrite<StudyDraftRecord>(`${studyPath(id)}/approve`, { revision, checksum })
export const importStudyDraft = (format: 'json' | 'csv', text: string) => studyWrite<StudyDraftRecord>('/api/study/import', { format, text })
export const importStudyQuestions = (id: string, revision: number, format: string, text: string, slideId: string) => studyWrite<StudyDraftRecord>(`${studyPath(id)}/questions`, { revision, format, text, slideId })
export const studyExportUrl = (id: string, format: 'json' | 'csv' | 'approved', checksum = '') => `${studyPath(id)}/export?${new URLSearchParams({ format, checksum })}`
export const exportStudy = (draftId: string, format: 'json' | 'csv' | 'approved', checksum: string, destination: string) =>
  studyWrite<ExportState>('/api/exports', { kind: 'study', draftId, format, checksum, destination })

export const cancelViewerOfflineDownload = (id: string) => request<void>(`/api/viewer/slides/${encodeURIComponent(id)}/offline/cancel`, { method: 'POST' })

export const uploadTeachingArtifact = (id: string) => request<ViewerUpload>(`/api/datasets/${encodeURIComponent(id)}/teaching-upload`, { method: 'POST' })
