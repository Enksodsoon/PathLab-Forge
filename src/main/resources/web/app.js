const state = {
  datasets: [],
  filter: 'all',
  query: '',
  theme: localStorage.getItem('pathlab-forge-theme') || 'system',
}

const emptyState = document.querySelector('[data-empty-state]')
const grid = document.querySelector('[data-dataset-grid]')
const queueStatus = document.querySelector('[data-queue-status]')
const toast = document.querySelector('[data-toast]')
const filterPanel = document.querySelector('.filter-panel')

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
  if (dataset.status === 'READY' && dataset.format === 'OME_TIFF') {
    actions.append(actionButton('Create managed copy', 'prepare', dataset))
  }
  if (dataset.status === 'LOCAL_COPY_READY' && dataset.outputPath) {
    actions.append(actionButton('Copy output path', 'copy-path', dataset))
  }
  actions.append(actionButton('Remove from library', 'remove', dataset, true))
  card.append(heading, details, badge, actions)
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
  const ready = state.datasets.filter((item) => item.status === 'LOCAL_COPY_READY').length
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
    render()
    if (message) notify(message)
  } catch (error) {
    notify(error.message)
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
    } else if (button.dataset.action === 'remove') {
      await request(`/api/datasets/${encodeURIComponent(dataset.id)}`, 'DELETE')
      await loadDatasets('Removed from the Forge library. The source file was not deleted.')
    } else if (button.dataset.action === 'copy-path') {
      await navigator.clipboard.writeText(dataset.outputPath)
      button.disabled = false
      notify('Managed output path copied.')
    }
  } catch (error) {
    button.disabled = false
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
loadDatasets()
