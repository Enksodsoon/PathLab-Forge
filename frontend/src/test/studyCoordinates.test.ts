import { describe, expect, it } from 'vitest'
import { teachingTarget } from '../studyCoordinates'
import { scoreStudyTask, type StudyTask } from '../StudyAuthoring'

describe('faculty geometry and scoring', () => {
  it('maps crop endpoints independently of downsampling and rejects out of bounds', () => {
    const crop = { x: 100, y: 200, width: 200, height: 100 }
    expect(teachingTarget({ x: 150, y: 250, width: 50, height: 50 }, crop, 50, 25)).toEqual({ targetX: .25, targetY: .5, targetWidth: .25, targetHeight: .5 })
    expect(() => teachingTarget({ x: 99, y: 250, width: 50, height: 50 }, crop, 50, 25)).toThrow(/outside/)
    expect(() => teachingTarget({ x: 250, y: 250, width: 51, height: 50 }, crop, 50, 25)).toThrow(/outside/)
  })
  it('supports explicit axis aligned registration and rejects shape changing affine maps', () => {
    const roi = { x: 10, y: 20, width: 10, height: 20 }, crop = { x: 0, y: 0, width: 100, height: 100 }
    expect(teachingTarget(roi, crop, 100, 100, [2, 0, 0, 2, 0, 0])).toEqual({ targetX: .2, targetY: .4, targetWidth: .2, targetHeight: .4 })
    expect(() => teachingTarget(roi, crop, 100, 100, [1, .2, 0, 1, 0, 0])).toThrow(/rectangle/)
  })
  it('requires explicit faculty content and normalized finite submissions', () => {
    const task = { type: 'spatial', targetX: .2, targetY: .3, targetWidth: .1, targetHeight: .2, tolerance: .01 } as StudyTask
    expect(scoreStudyTask(task, { x: .25, y: .4 }).correct).toBe(true)
    expect(scoreStudyTask(task, { x: .9, y: .9 }).correct).toBe(false)
    expect(() => scoreStudyTask({ type: 'multiple-choice' } as StudyTask, {})).toThrow(/key/)
    expect(() => scoreStudyTask(task, { x: Infinity, y: .4 })).toThrow(/finite/)
  })
})
