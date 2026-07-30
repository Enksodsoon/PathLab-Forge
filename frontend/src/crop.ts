export interface CropBox {
  x: number
  y: number
  width: number
  height: number
}

export type CropHandle = 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w' | 'nw'

export interface LiveCropEstimate {
  outputWidth: number
  outputHeight: number
  fileBytes: number
  fileLowerBytes: number
  fileUpperBytes: number
  workspaceBytes: number
}

export function cropFromPoints(
  start: { x: number; y: number },
  end: { x: number; y: number },
  sourceWidth: number,
  sourceHeight: number,
): CropBox {
  const left = clamp(Math.round(Math.min(start.x, end.x)), 0, Math.max(0, sourceWidth - 1))
  const top = clamp(Math.round(Math.min(start.y, end.y)), 0, Math.max(0, sourceHeight - 1))
  const right = clamp(Math.round(Math.max(start.x, end.x)), left + 1, sourceWidth)
  const bottom = clamp(Math.round(Math.max(start.y, end.y)), top + 1, sourceHeight)
  return { x: left, y: top, width: right - left, height: bottom - top }
}

export function moveCrop(
  box: CropBox,
  deltaX: number,
  deltaY: number,
  sourceWidth: number,
  sourceHeight: number,
): CropBox {
  return {
    ...box,
    x: clamp(Math.round(box.x + deltaX), 0, Math.max(0, sourceWidth - box.width)),
    y: clamp(Math.round(box.y + deltaY), 0, Math.max(0, sourceHeight - box.height)),
  }
}

export function resizeCrop(
  box: CropBox,
  handle: CropHandle,
  deltaX: number,
  deltaY: number,
  sourceWidth: number,
  sourceHeight: number,
): CropBox {
  let left = box.x
  let top = box.y
  let right = box.x + box.width
  let bottom = box.y + box.height

  if (handle.includes('w')) left = clamp(Math.round(left + deltaX), 0, right - 1)
  if (handle.includes('e')) right = clamp(Math.round(right + deltaX), left + 1, sourceWidth)
  if (handle.includes('n')) top = clamp(Math.round(top + deltaY), 0, bottom - 1)
  if (handle.includes('s')) bottom = clamp(Math.round(bottom + deltaY), top + 1, sourceHeight)

  return { x: left, y: top, width: right - left, height: bottom - top }
}

export function isFullSlideCrop(
  box: CropBox,
  sourceWidth: number,
  sourceHeight: number,
) {
  return box.x === 0
    && box.y === 0
    && box.width === sourceWidth
    && box.height === sourceHeight
}

export function estimateCropOutput(
  box: CropBox,
  downsample: number,
  sourceBytes: number,
  sourceIsOmeTiff: boolean,
): LiveCropEstimate {
  const outputWidth = Math.max(1, Math.floor(box.width / downsample))
  const outputHeight = Math.max(1, Math.floor(box.height / downsample))
  const basePixels = outputWidth * outputHeight
  const pyramidPixels = basePixels * 4 / 3
  const wholeSlideFactor = clamp(Math.log10(Math.max(1, basePixels / 1_000_000)) / 3, 0, 1)
  const pixelLower = saturatedRound(pyramidPixels * (0.25 + ((0.025 - 0.25) * wholeSlideFactor)))
  const pixelUpper = saturatedRound(pyramidPixels * (1.20 + ((0.65 - 1.20) * wholeSlideFactor)))
  const scaledSource = sourceBytes === 0
    ? 0
    : saturatedRound(sourceBytes / (downsample * downsample))

  let fileLowerBytes = pixelLower
  let fileUpperBytes: number
  let fileBytes: number
  if (sourceIsOmeTiff && scaledSource > 0) {
    fileLowerBytes = Math.max(pixelLower, saturatedRound(scaledSource * 0.5))
    fileUpperBytes = Math.max(fileLowerBytes, Math.min(pixelUpper, saturatedRound(scaledSource * 2)))
    fileBytes = clamp(scaledSource, fileLowerBytes, fileUpperBytes)
  } else {
    const sourceCeiling = scaledSource === 0
      ? pixelUpper
      : saturatedRound(scaledSource * 1.25)
    fileUpperBytes = Math.max(fileLowerBytes, Math.min(pixelUpper, sourceCeiling))
    fileBytes = saturatedRound(Math.sqrt(fileLowerBytes * fileUpperBytes))
  }

  return {
    outputWidth,
    outputHeight,
    fileBytes,
    fileLowerBytes,
    fileUpperBytes,
    workspaceBytes: Math.round(basePixels * 4),
  }
}

function saturatedRound(value: number) {
  if (!Number.isFinite(value) || value >= Number.MAX_SAFE_INTEGER) return Number.MAX_SAFE_INTEGER
  return Math.max(1, Math.round(value))
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}
