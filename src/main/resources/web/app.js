const state = {
  datasets: [],
  filter: 'all',
  query: '',
  theme: localStorage.getItem('pathlab-forge-theme') || 'system',
  series: {},
  capabilities: {},
  viewer: null,
  viewerDataset: null,
  annotations: [],
  annotationTool: 'pan',
  draft: null,
}

const emptyState = document.querySelector('[data-empty-state]')
const grid = document.querySelector('[data-dataset-grid]')
const queueStatus = document.querySelector('[data-queue-status]')
const toast = document.querySelector('[data-toast]')
const filterPanel = document.querySelector('.filter-panel')
const runtime = document.querySelector('[data-runtime]')

function notify(message) {
  toast.textContent = message
  toast.hidden = false
  clearTimeout(notify.timer)
  notify.timer = setTimeout(() => { toast.hidden = true }, 5000)
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unit = units[0]
  for (let index = 1; value >= 1024 && index < units.length; index += 1) {
    value /= 1024
    unit = units[index]
  }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${unit}`
}

function statusLabel(status) {
  return {
    READY: 'Ready to prepare',
    NEEDS_COMPANIONS: 'Companions missing',
    READER_REQUIRED: 'VSI reader required',
    INSPECTING: 'Inspecting metadata',
    READY_TO_CONVERT: 'Ready to export',
    CONVERTING: 'Converting locally',
    VALIDATING: 'Validating output',
    GENERATING_DZI: 'Generating DZI',
    DZI_READY: 'DZI ready',
    PACKAGE_READY: 'Viewer package ready',
    CONVERSION_READY: 'OME-TIFF ready',
    CANCELLED: 'Cancelled',
    LOCAL_COPY_READY: 'Managed copy ready',
    FAILED: 'Preparation failed',
  }[status] || status.toLowerCase().replaceAll('_', ' ')
}

function actionButton(label, action, dataset, secondary = false) {
  const button = document.createElement('button')
  button.textContent = label
  button.dataset.action = action
  button.dataset.id = dataset.id
  if (secondary) button.className = 'secondary'
  return button
}

function datasetCard(dataset) {
  const card = document.createElement('article')
  card.className = 'dataset-card'
  card.dataset.datasetId = dataset.id
  const heading = document.createElement('div')
  heading.className = 'card-heading'
  const title = document.createElement('h3')
  title.textContent = dataset.displayName
  const type = document.createElement('span')
  type.className = 'type'
  type.textContent = dataset.format === 'VSI' ? 'VSI / ETS' : 'OME-TIFF'
  heading.append(title, type)
  const details = document.createElement('p')
  details.textContent = `${formatBytes(dataset.sourceBytes)} · ${dataset.detail}`
  const badge = document.createElement('span')
  badge.className = `badge status-${dataset.status.toLowerCase()}`
  badge.textContent = statusLabel(dataset.status)
  const actions = document.createElement('div')
  actions.className = 'card-actions'
  const series = state.series[dataset.id] || []
  if (series.length) {
    const control = document.createElement('div')
    control.className = 'series-control'
    const label = document.createElement('label')
    label.textContent = 'Image series'
    const select = document.createElement('select')
    select.dataset.action = 'select-series'
    select.dataset.id = dataset.id
    series.forEach((item) => {
      const option = document.createElement('option')
      option.value = item.index
      option.selected = item.index === dataset.selectedSeries
      option.disabled = !item.rgbPlane
      option.textContent = `${item.name || `Series ${item.index}`} · ${item.width} × ${item.height}${item.rgbPlane ? '' : ' · not 2D RGB'}`
      select.append(option)
    })
    control.append(label, select)
    card.append(heading, details, badge, control)
  } else {
    card.append(heading, details, badge)
  }
  if (dataset.estimatedOutputBytes > 0) {
    const estimate = document.createElement('p')
    estimate.className = 'estimate'
    estimate.textContent = `${dataset.width.toLocaleString()} × ${dataset.height.toLocaleString()} · storage upper bound ${formatBytes(dataset.estimatedOutputBytes)}`
    card.append(estimate)
  }
  if (series.length && dataset.selectedSeries >= 0) {
    const exportGrid = document.createElement('div')
    exportGrid.className = 'export-grid'
    const fields = [
      ['x', 'X', dataset.cropX],
      ['y', 'Y', dataset.cropY],
      ['width', 'Width', dataset.cropWidth || dataset.width],
      ['height', 'Height', dataset.cropHeight || dataset.height],
    ]
    fields.forEach(([name, labelText, value]) => {
      const label = document.createElement('label')
      label.textContent = labelText
      const input = document.createElement('input')
      input.type = 'number'
      input.min = '0'
      input.step = '1'
      input.value = value
      input.dataset.exportField = name
      label.append(input)
      exportGrid.append(label)
    })
    const downsampleLabel = document.createElement('label')
    downsampleLabel.textContent = 'Downsample'
    const downsample = document.createElement('select')
    downsample.dataset.exportField = 'downsample'
    ;[1, 2, 4, 8].forEach((value) => {
      const option = document.createElement('option')
      option.value = value
      option.textContent = `${value}×`
      option.selected = value === dataset.downsample
      downsample.append(option)
    })
    downsampleLabel.append(downsample)
    exportGrid.append(downsampleLabel)
    card.append(exportGrid)
  }
  if (dataset.status === 'READY' && dataset.format === 'OME_TIFF') {
    actions.append(actionButton('Create managed copy', 'prepare', dataset))
  }
  if (dataset.format === 'VSI' && !['CONVERTING', 'VALIDATING', 'GENERATING_DZI'].includes(dataset.status)) {
    actions.append(actionButton(series.length ? 'Re-inspect series' : 'Inspect series', 'inspect', dataset))
  }
  if (dataset.status === 'READY_TO_CONVERT') {
    actions.append(actionButton('Update crop & estimate', 'configure', dataset, true))
    actions.append(actionButton('Export RGB OME-TIFF', 'convert', dataset))
  }
  if (['CONVERTING', 'VALIDATING', 'GENERATING_DZI'].includes(dataset.status)) {
    actions.append(actionButton('Cancel conversion', 'cancel', dataset, true))
  }
  if (['LOCAL_COPY_READY', 'CONVERSION_READY', 'DZI_READY', 'PACKAGE_READY'].includes(dataset.status) && dataset.outputPath) {
    actions.append(actionButton('Copy output path', 'copy-path', dataset))
  }
  if (['DZI_READY', 'PACKAGE_READY'].includes(dataset.status)) {
    actions.append(actionButton('Open slide viewer', 'open-viewer', dataset))
  }
  if (dataset.status === 'PACKAGE_READY') {
    actions.append(actionButton('Download .plslide', 'download-package', dataset, true))
  }
  actions.append(actionButton('Remove from library', 'remove', dataset, true))
  card.append(actions)
  return card
}

function render() {
  const visible = state.datasets.filter((dataset) => {
    const type = dataset.format === 'VSI' ? 'vsi' : 'ome'
    const matchesFilter = state.filter === 'all' || type === state.filter
    return matchesFilter && dataset.displayName.toLowerCase().includes(state.query)
  })
  emptyState.hidden = state.datasets.length > 0
  grid.hidden = state.datasets.length === 0
  grid.replaceChildren(...visible.map(datasetCard))
  const ready = state.datasets.filter(
    (item) => ['LOCAL_COPY_READY', 'CONVERSION_READY', 'DZI_READY', 'PACKAGE_READY'].includes(item.status),
  ).length
  const blocked = state.datasets.filter(
    (item) => item.status === 'READER_REQUIRED' || item.status === 'NEEDS_COMPANIONS',
  ).length
  queueStatus.textContent = `${state.datasets.length} local · ${ready} ready${blocked ? ` · ${blocked} blocked` : ''}`
}

async function csrfToken() {
  const response = await fetch('/api/session')
  if (!response.ok) throw new Error('Local session expired. Restart Forge to receive a new launch link.')
  return response.headers.get('X-Forge-CSRF')
}

async function request(path, method = 'GET') {
  const headers = {}
  if (method !== 'GET') headers['X-Forge-CSRF'] = await csrfToken()
  const response = await fetch(path, {method, headers})
  if (!response.ok) {
    let body = {}
    try { body = await response.json() } catch (_) { /* no structured error */ }
    throw new Error(body.detail || body.error || `Local request failed (${response.status})`)
  }
  if (response.status === 204) return null
  return response.json()
}

async function loadDatasets(message) {
  try {
    const body = await request('/api/datasets')
    state.datasets = body.datasets
    await Promise.all(body.datasets
      .filter((dataset) => dataset.selectedSeries >= 0 && !state.series[dataset.id])
      .map(async (dataset) => {
        const result = await request(`/api/datasets/${encodeURIComponent(dataset.id)}/series`)
        if (result.series.length) state.series[dataset.id] = result.series
      }))
    render()
    if (message) notify(message)
  } catch (error) {
    notify(error.message)
  }
}

async function loadCapabilities() {
  try {
    state.capabilities = await request('/api/capabilities')
    runtime.classList.toggle('ready', state.capabilities.vsiConversion)
    runtime.lastElementChild.textContent = state.capabilities.vsiConversion
      ? `${state.capabilities.conversionRuntime} · one local conversion at a time`
      : `${state.capabilities.conversionRuntime} · OME-TIFF managed copies remain available`
  } catch (error) {
    runtime.lastElementChild.textContent = error.message
  }
}

async function chooseDatasets() {
  try {
    const body = await request('/api/datasets/select', 'POST')
    state.datasets = body.datasets
    render()
    notify('Local selection inspected and saved.')
  } catch (error) {
    notify(error.message)
  }
}

grid.addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-action]')
  if (!button) return
  const dataset = state.datasets.find((item) => item.id === button.dataset.id)
  if (!dataset) return
  button.disabled = true
  try {
    if (button.dataset.action === 'prepare') {
      await request(`/api/datasets/${encodeURIComponent(dataset.id)}/prepare`, 'POST')
      await loadDatasets('Managed OME-TIFF copy created and verified.')
    } else if (button.dataset.action === 'inspect') {
      const body = await request(`/api/datasets/${encodeURIComponent(dataset.id)}/inspect`, 'POST')
      state.series[dataset.id] = body.series
      await loadDatasets('Image series inspected. Choose a 2D RGB series to export.')
    } else if (button.dataset.action === 'convert') {
      await request(`/api/datasets/${encodeURIComponent(dataset.id)}/convert`, 'POST')
      await loadDatasets('Conversion started in the local single-slide queue.')
    } else if (button.dataset.action === 'configure') {
      const card = button.closest('.dataset-card')
      const values = Object.fromEntries([...card.querySelectorAll('[data-export-field]')]
        .map((input) => [input.dataset.exportField, input.value]))
      const params = new URLSearchParams({
        series: String(dataset.selectedSeries),
        downsample: values.downsample,
        x: values.x,
        y: values.y,
        width: values.width,
        height: values.height,
      })
      await request(
        `/api/datasets/${encodeURIComponent(dataset.id)}/series?${params}`,
        'POST',
      )
      await loadDatasets('Crop, downsample, and storage estimate updated.')
    } else if (button.dataset.action === 'cancel') {
      await request(`/api/datasets/${encodeURIComponent(dataset.id)}/cancel`, 'POST')
      await loadDatasets('Conversion cancelled. Completed checkpoints were preserved.')
    } else if (button.dataset.action === 'remove') {
      await request(`/api/datasets/${encodeURIComponent(dataset.id)}`, 'DELETE')
      await loadDatasets('Removed from the Forge library. The source file was not deleted.')
    } else if (button.dataset.action === 'copy-path') {
      await navigator.clipboard.writeText(dataset.outputPath)
      button.disabled = false
      notify('Managed output path copied.')
    } else if (button.dataset.action === 'open-viewer') {
      button.disabled = false
      await openViewer(dataset)
    } else if (button.dataset.action === 'download-package') {
      button.disabled = false
      window.location.assign(`/api/datasets/${encodeURIComponent(dataset.id)}/package`)
    }
  } catch (error) {
    button.disabled = false
    notify(error.message)
  }
})

grid.addEventListener('change', async (event) => {
  const select = event.target.closest('select[data-action="select-series"]')
  if (!select) return
  select.disabled = true
  try {
    await request(
      `/api/datasets/${encodeURIComponent(select.dataset.id)}/series?series=${encodeURIComponent(select.value)}&downsample=1`,
      'POST',
    )
    await loadDatasets('Series selected and storage estimate updated.')
  } catch (error) {
    select.disabled = false
    notify(error.message)
  }
})

document.querySelectorAll('[data-action="add-datasets"]').forEach((button) => {
  button.addEventListener('click', chooseDatasets)
})
document.querySelector('[data-action="refresh"]').addEventListener('click', () => {
  loadDatasets('Library refreshed from disk.')
})
document.querySelector('#library-search').addEventListener('input', (event) => {
  state.query = event.target.value.trim().toLowerCase()
  render()
})
document.querySelector('[data-action="filters"]').addEventListener('click', (event) => {
  filterPanel.hidden = !filterPanel.hidden
  event.currentTarget.setAttribute('aria-pressed', String(!filterPanel.hidden))
})
document.querySelectorAll('[data-filter]').forEach((button) => {
  button.addEventListener('click', () => {
    state.filter = button.dataset.filter
    document.querySelectorAll('[data-filter]').forEach((item) => {
      item.classList.toggle('active', item === button)
    })
    render()
  })
})
document.querySelector('[data-action="theme"]').addEventListener('click', () => {
  const themes = ['system', 'light', 'dark']
  state.theme = themes[(themes.indexOf(state.theme) + 1) % themes.length]
  localStorage.setItem('pathlab-forge-theme', state.theme)
  applyTheme()
})

function applyTheme() {
  if (state.theme === 'system') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = state.theme
  document.querySelector('[data-action="theme"]').textContent =
    `Theme: ${state.theme[0].toUpperCase()}${state.theme.slice(1)}`
}

applyTheme()
loadCapabilities()
loadDatasets()
setInterval(() => {
  if (state.datasets.some((item) => ['CONVERTING', 'VALIDATING', 'GENERATING_DZI', 'DZI_READY', 'INSPECTING'].includes(item.status))) {
    loadDatasets()
  }
}, 2000)

const viewerDialog = document.querySelector('[data-viewer-dialog]')
const viewerElement = document.querySelector('[data-slide-viewer]')
const annotationOverlay = document.querySelector('[data-annotation-overlay]')
const annotationList = document.querySelector('[data-annotation-list]')
const annotationLabel = document.querySelector('[data-annotation-label]')
const annotationColor = document.querySelector('[data-annotation-color]')
const toolHint = document.querySelector('[data-tool-hint]')
const svgNamespace = 'http://www.w3.org/2000/svg'

async function openViewer(dataset) {
  if (!window.OpenSeadragon) throw new Error('The local slide viewer runtime did not load.')
  if (state.viewer) {
    state.viewer.destroy()
    state.viewer = null
  }
  state.viewerDataset = dataset
  state.annotationTool = 'pan'
  state.draft = null
  document.querySelector('[data-viewer-title]').textContent = dataset.displayName
  viewerDialog.showModal()
  state.viewer = OpenSeadragon({
    element: viewerElement,
    tileSources: `/api/datasets/${encodeURIComponent(dataset.id)}/derivative/slide.dzi`,
    showNavigationControl: false,
    showNavigator: true,
    navigatorPosition: 'BOTTOM_RIGHT',
    animationTime: 0.8,
    blendTime: 0.1,
    maxZoomPixelRatio: 4,
    gestureSettingsMouse: {clickToZoom: false},
  })
  state.viewer.addHandler('open', renderAnnotations)
  state.viewer.addHandler('animation', renderAnnotations)
  state.viewer.addHandler('resize', renderAnnotations)
  state.viewer.addHandler('open-failed', () => notify('The local DZI could not be opened.'))
  await loadAnnotations()
  selectTool('pan')
}

function closeViewer() {
  if (state.viewer) state.viewer.destroy()
  state.viewer = null
  state.viewerDataset = null
  state.annotations = []
  state.draft = null
  viewerDialog.close()
}

async function loadAnnotations() {
  if (!state.viewerDataset) return
  const body = await request(`/api/datasets/${encodeURIComponent(state.viewerDataset.id)}/annotations`)
  state.annotations = body.annotations
  renderAnnotations()
  renderAnnotationList()
}

function selectTool(tool) {
  state.annotationTool = tool
  state.draft = null
  document.querySelectorAll('[data-tool]').forEach((button) => {
    button.classList.toggle('active', button.dataset.tool === tool)
  })
  const drawing = tool !== 'pan'
  annotationOverlay.classList.toggle('drawing', drawing)
  if (state.viewer) state.viewer.setMouseNavEnabled(!drawing)
  toolHint.textContent = {
    pan: 'Pan and zoom the slide.',
    point: 'Click once to place a point.',
    text: 'Enter a label, then click to place it.',
    rectangle: 'Drag between opposite corners.',
    ellipse: 'Drag the ellipse bounds.',
    line: 'Drag from the start to the end.',
    measure: 'Drag a line to measure image pixels.',
    polygon: 'Click vertices, then double-click to finish.',
    freehand: 'Press and draw a freehand boundary.',
  }[tool]
  renderAnnotations()
}

function imagePoint(event) {
  if (!state.viewer || !state.viewer.world.getItemCount()) return null
  const bounds = annotationOverlay.getBoundingClientRect()
  const screen = new OpenSeadragon.Point(event.clientX - bounds.left, event.clientY - bounds.top)
  const image = state.viewer.world.getItemAt(0).viewerElementToImageCoordinates(screen)
  return {x: Math.max(0, image.x), y: Math.max(0, image.y)}
}

function screenPoint(point) {
  if (!state.viewer || !state.viewer.world.getItemCount()) return null
  return state.viewer.world.getItemAt(0).imageToViewerElementCoordinates(
    new OpenSeadragon.Point(point.x, point.y),
  )
}

function parseGeometry(value) {
  return value.split(';').map((pair) => {
    const [x, y] = pair.split(',').map(Number)
    return {x, y}
  })
}

function geometryValue(points) {
  return points.map((point) => `${point.x.toFixed(3)},${point.y.toFixed(3)}`).join(';')
}

function annotationShape(annotation, draft = false) {
  const imagePoints = annotation.points || parseGeometry(annotation.geometry)
  const points = imagePoints.map(screenPoint).filter(Boolean)
  if (!points.length) return null
  const color = annotation.color || annotationColor.value
  const common = {fill: 'none', stroke: color, 'stroke-width': draft ? '2' : '2.5', 'vector-effect': 'non-scaling-stroke'}
  let shape
  if (annotation.type === 'point') {
    shape = document.createElementNS(svgNamespace, 'circle')
    shape.setAttribute('cx', points[0].x)
    shape.setAttribute('cy', points[0].y)
    shape.setAttribute('r', '6')
    common.fill = color
  } else if (annotation.type === 'rectangle' && points[1]) {
    shape = document.createElementNS(svgNamespace, 'rect')
    shape.setAttribute('x', Math.min(points[0].x, points[1].x))
    shape.setAttribute('y', Math.min(points[0].y, points[1].y))
    shape.setAttribute('width', Math.abs(points[1].x - points[0].x))
    shape.setAttribute('height', Math.abs(points[1].y - points[0].y))
  } else if (annotation.type === 'ellipse' && points[1]) {
    shape = document.createElementNS(svgNamespace, 'ellipse')
    shape.setAttribute('cx', (points[0].x + points[1].x) / 2)
    shape.setAttribute('cy', (points[0].y + points[1].y) / 2)
    shape.setAttribute('rx', Math.abs(points[1].x - points[0].x) / 2)
    shape.setAttribute('ry', Math.abs(points[1].y - points[0].y) / 2)
  } else if (['line', 'measure'].includes(annotation.type) && points[1]) {
    shape = document.createElementNS(svgNamespace, 'line')
    shape.setAttribute('x1', points[0].x)
    shape.setAttribute('y1', points[0].y)
    shape.setAttribute('x2', points[1].x)
    shape.setAttribute('y2', points[1].y)
  } else if (['polygon', 'freehand'].includes(annotation.type) && points[1]) {
    shape = document.createElementNS(svgNamespace, annotation.type === 'polygon' ? 'polygon' : 'polyline')
    shape.setAttribute('points', points.map((point) => `${point.x},${point.y}`).join(' '))
    if (annotation.type === 'polygon') common.fill = `${color}22`
  } else if (annotation.type === 'text') {
    shape = document.createElementNS(svgNamespace, 'text')
    shape.setAttribute('x', points[0].x + 8)
    shape.setAttribute('y', points[0].y - 8)
    shape.textContent = annotation.label || 'Note'
    common.fill = color
    common.stroke = 'none'
  }
  if (!shape) return null
  Object.entries(common).forEach(([name, value]) => shape.setAttribute(name, value))
  shape.dataset.annotationId = annotation.id || ''
  return shape
}

function renderAnnotations() {
  const shapes = state.annotations.map((annotation) => annotationShape(annotation)).filter(Boolean)
  if (state.draft) {
    const draftShape = annotationShape(state.draft, true)
    if (draftShape) shapes.push(draftShape)
  }
  annotationOverlay.replaceChildren(...shapes)
}

function renderAnnotationList() {
  if (!state.annotations.length) {
    const empty = document.createElement('p')
    empty.className = 'annotation-empty'
    empty.textContent = 'No annotations yet.'
    annotationList.replaceChildren(empty)
    return
  }
  annotationList.replaceChildren(...state.annotations.map((annotation, index) => {
    const row = document.createElement('div')
    row.className = 'annotation-row'
    const text = document.createElement('span')
    text.textContent = annotation.label || `${annotation.type} ${index + 1}`
    const remove = document.createElement('button')
    remove.className = 'secondary'
    remove.textContent = 'Delete'
    remove.dataset.deleteAnnotation = annotation.id
    row.append(text, remove)
    return row
  }))
}

async function saveAnnotation(type, points) {
  if (!state.viewerDataset || !points.length) return
  const params = new URLSearchParams({
    type,
    geometry: geometryValue(points),
    label: annotationLabel.value.trim(),
    color: annotationColor.value,
  })
  await request(
    `/api/datasets/${encodeURIComponent(state.viewerDataset.id)}/annotations?${params}`,
    'POST',
  )
  state.draft = null
  await loadAnnotations()
  notify('Annotation saved locally.')
}

annotationOverlay.addEventListener('pointerdown', async (event) => {
  if (state.annotationTool === 'pan') return
  const point = imagePoint(event)
  if (!point) return
  if (['point', 'text'].includes(state.annotationTool)) {
    await saveAnnotation(state.annotationTool, [point])
    return
  }
  if (state.annotationTool === 'polygon') {
    if (!state.draft) state.draft = {type: 'polygon', points: [], color: annotationColor.value}
    state.draft.points.push(point)
    renderAnnotations()
    return
  }
  annotationOverlay.setPointerCapture(event.pointerId)
  state.draft = {type: state.annotationTool, points: [point, point], color: annotationColor.value}
  renderAnnotations()
})

annotationOverlay.addEventListener('pointermove', (event) => {
  if (!state.draft || state.draft.type === 'polygon' || !annotationOverlay.hasPointerCapture(event.pointerId)) return
  const point = imagePoint(event)
  if (!point) return
  if (state.draft.type === 'freehand') state.draft.points.push(point)
  else state.draft.points[1] = point
  renderAnnotations()
})

annotationOverlay.addEventListener('pointerup', async (event) => {
  if (!state.draft || state.draft.type === 'polygon') return
  if (annotationOverlay.hasPointerCapture(event.pointerId)) annotationOverlay.releasePointerCapture(event.pointerId)
  const draft = state.draft
  state.draft = null
  await saveAnnotation(draft.type, draft.points)
})

annotationOverlay.addEventListener('dblclick', async (event) => {
  event.preventDefault()
  if (!state.draft || state.draft.type !== 'polygon') return
  const unique = state.draft.points.filter((point, index, points) =>
    index === 0 || point.x !== points[index - 1].x || point.y !== points[index - 1].y)
  if (unique.length < 3) {
    notify('A polygon needs at least three vertices.')
    return
  }
  state.draft = null
  await saveAnnotation('polygon', unique)
})

document.querySelectorAll('[data-tool]').forEach((button) => {
  button.addEventListener('click', () => selectTool(button.dataset.tool))
})
document.querySelectorAll('[data-viewer-action]').forEach((button) => {
  button.addEventListener('click', () => {
    const action = button.dataset.viewerAction
    if (action === 'close') closeViewer()
    else if (action === 'home') state.viewer?.viewport.goHome()
    else if (action === 'zoom-in') state.viewer?.viewport.zoomBy(1.5)
    else if (action === 'zoom-out') state.viewer?.viewport.zoomBy(0.67)
    else if (action === 'fullscreen') state.viewer?.setFullScreen(!state.viewer.isFullPage())
    state.viewer?.viewport.applyConstraints()
  })
})
annotationList.addEventListener('click', async (event) => {
  const button = event.target.closest('[data-delete-annotation]')
  if (!button || !state.viewerDataset) return
  await request(
    `/api/datasets/${encodeURIComponent(state.viewerDataset.id)}/annotations/${encodeURIComponent(button.dataset.deleteAnnotation)}`,
    'DELETE',
  )
  await loadAnnotations()
  notify('Annotation deleted.')
})
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && viewerDialog.open && state.draft) {
    state.draft = null
    renderAnnotations()
  }
})
