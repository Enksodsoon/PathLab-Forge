import { expect, it } from 'vitest'
import { captureTeachingTarget, teachingAssociationFor, type TeachingAssociation } from '../teachingAssociations'
import type { AnnotationRecord } from '../api'

it('captures only an exact saved rectangle plane in the immutable teaching crop', () => {
  const association: TeachingAssociation = { referenceId: 'local:reference', viewerSlideId: '',
    datasetId: 'ca38d59a-08ce-44a2-aaf2-cb96bd147bdf', artifactRevision: 'fcafc4bf-2350-470c-aa3a-ad5f5a0d9734',
    packageSha256: 'a'.repeat(64), configurationRevision: 'config', sourceFingerprint: 'source', outputWidth: 100, outputHeight: 50,
    provenance: { series: 2, viewRevision: 'view', crop: { x: 10, y: 20, width: 200, height: 100 }, downsample: 2,
      viewDefinition: { z: { mode: 'SLICE', start: 3, end: 3 }, t: { mode: 'SLICE', start: 4, end: 4 } } } }
  const roi = { type: 'rectangle', geometry: '30,40;70,60', series: 2, z: 3, t: 4, viewRevision: 'view' } as AnnotationRecord
  expect(captureTeachingTarget(roi, association.datasetId, 'source', association)).toEqual({ targetX: .1, targetY: .2, targetWidth: .2, targetHeight: .2 })
  expect(() => captureTeachingTarget({ ...roi, z: 0 }, association.datasetId, 'source', association)).toThrow('exact source plane')
  expect(() => captureTeachingTarget({ ...roi, geometry: '0,0;50,50' }, association.datasetId, 'source', association)).toThrow('outside')
  const slide = { viewerSlideId: association.referenceId, sha256: association.packageSha256, displayName: 'Local slide' }
  expect(teachingAssociationFor(slide, { teachingSlides: { [slide.viewerSlideId]: association } })).toEqual(association)
  expect(teachingAssociationFor({ ...slide, sha256: 'b'.repeat(64) }, { teachingSlides: { [slide.viewerSlideId]: association } })).toBeNull()
})
