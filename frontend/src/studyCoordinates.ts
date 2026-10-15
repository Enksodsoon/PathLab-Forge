export interface StudyRectangle { x: number; y: number; width: number; height: number }
/** Pixel source ROI -> selected teaching crop -> normalized published coordinates.
 * Affine tuple follows x'=a*x+c*y+e, y'=b*x+d*y+f. It must describe
 * an explicitly selected registration that preserves an axis aligned rectangle.
 */
export function teachingTarget(roi: StudyRectangle, crop: StudyRectangle, artifactWidth: number, artifactHeight: number,
  affine: readonly [number, number, number, number, number, number] = [1, 0, 0, 1, 0, 0]) {
  for (const rectangle of [roi, crop]) {
    if (!Object.values(rectangle).every(Number.isFinite) || rectangle.x < 0 || rectangle.y < 0 || rectangle.width <= 0 || rectangle.height <= 0) throw new Error('Source ROI and teaching crop must contain finite positive geometry.')
  }
  if (![artifactWidth, artifactHeight].every((value) => Number.isSafeInteger(value) && value > 0) || !affine.every(Number.isFinite)) throw new Error('Teaching artifact dimensions and transform are invalid.')
  const [a, b, c, d, e, f] = affine
  if (!((b === 0 && c === 0 && a !== 0 && d !== 0) || (a === 0 && d === 0 && b !== 0 && c !== 0))) throw new Error('Registration must preserve an axis aligned rectangle; correct the target manually.')
  const corners = [[roi.x, roi.y], [roi.x + roi.width, roi.y], [roi.x, roi.y + roi.height], [roi.x + roi.width, roi.y + roi.height]].map(([x, y]) => ({ x: a * x + c * y + e, y: b * x + d * y + f }))
  if (corners.some(({ x, y }) => !Number.isFinite(x) || !Number.isFinite(y) || x < crop.x || y < crop.y || x > crop.x + crop.width || y > crop.y + crop.height)) throw new Error('Selected ROI endpoints fall outside the teaching crop.')
  const left = Math.min(...corners.map(({ x }) => x)), right = Math.max(...corners.map(({ x }) => x))
  const top = Math.min(...corners.map(({ y }) => y)), bottom = Math.max(...corners.map(({ y }) => y))
  // Downsampling changes pixel dimensions, while the normalized location is invariant.
  return { targetX: (left - crop.x) / crop.width, targetY: (top - crop.y) / crop.height,
    targetWidth: (right - left) / crop.width, targetHeight: (bottom - top) / crop.height }
}
