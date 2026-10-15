import type { AnnotationRecord } from './api'
import type { StudySlide } from './StudyAuthoring'
import { parseGeometry } from './annotationGeometry'
import { teachingTarget } from './studyCoordinates'

export interface TeachingAssociation {
  referenceId: string; viewerSlideId: string; datasetId: string; artifactRevision: string; packageSha256: string
  configurationRevision: string; sourceFingerprint: string; outputWidth: number; outputHeight: number
  provenance: { series: number; viewRevision: string; crop: { x: number; y: number; width: number; height: number }; downsample: number
    viewDefinition?: { z: { mode: string; start: number; end: number }; t: { mode: string; start: number; end: number } } | null }
}
export interface TeachingPixels {
  previewChecksum: string; slideId: string; datasetId: string; artifactRevision: string; packageSha256: string
}
export function teachingAssociationFor(slide: StudySlide | undefined, associations: Record<string, unknown>): TeachingAssociation | null {
  if (!slide) return null
  const map = associations.teachingSlides as Record<string, TeachingAssociation> | undefined
  const value = map?.[slide.viewerSlideId]
  if (!value || value.packageSha256 !== slide.sha256 || !/^[a-f0-9]{64}$/.test(value.packageSha256)
    || ![value.referenceId, value.viewerSlideId].includes(slide.viewerSlideId)
    || !/^[a-fA-F0-9-]{36}$/.test(value.datasetId) || !/^[a-fA-F0-9-]{36}$/.test(value.artifactRevision)
    || ![value.outputWidth, value.outputHeight].every((size) => Number.isSafeInteger(size) && size > 0)) return null
  return value
}
export function pixelsMatch(pixels: TeachingPixels | null, checksum: string, slide: StudySlide | undefined, associations: Record<string, unknown>) {
  const association = teachingAssociationFor(slide, associations)
  return Boolean(pixels && association && pixels.previewChecksum === checksum && pixels.slideId === slide!.viewerSlideId
    && pixels.packageSha256 === association.packageSha256 && pixels.datasetId === association.datasetId && pixels.artifactRevision === association.artifactRevision)
}
export function captureTeachingTarget(roi: AnnotationRecord, datasetId: string, sourceFingerprint: string, association: TeachingAssociation) {
  const plane = association.provenance.viewDefinition
  const z = plane?.z.start ?? 0, t = plane?.t.start ?? 0
  if (datasetId !== association.datasetId || sourceFingerprint !== association.sourceFingerprint || roi.type !== 'rectangle'
    || roi.series !== association.provenance.series || roi.z !== z || roi.t !== t || roi.viewRevision !== association.provenance.viewRevision
    || (plane && (plane.z.mode !== 'SLICE' || plane.t.mode !== 'SLICE'))) {
    throw new Error('Select a saved rectangle on the exact source plane used by this teaching artifact.')
  }
  const points = parseGeometry(roi.geometry)
  if (points.length !== 2) throw new Error('A saved rectangle with two source endpoints is required.')
  return teachingTarget({ x: Math.min(points[0].x, points[1].x), y: Math.min(points[0].y, points[1].y),
    width: Math.abs(points[1].x - points[0].x), height: Math.abs(points[1].y - points[0].y) },
  association.provenance.crop, association.outputWidth, association.outputHeight)
}
