const state = {
  datasets: [],
  filter: 'all',
  query: '',
  theme: localStorage.getItem('pathlab-forge-theme') || 'system',
}

const picker = document.querySelector('#dataset-picker')
const emptyState = document.querySelector('[data-empty-state]')
const grid = document.querySelector('[data-dataset-grid]')
const queueStatus = document.querySelector('[data-queue-status]')
const toast = document.querySelector('[data-toast]')
const filterPanel = document.querySelector('.filter-panel')
const viewerDialog = document.querySelector('#viewer-dialog')

function notify(message) {
  toast.textContent = message
  toast.hidden = false
  clearTimeout(notify.timer)
  notify.timer = setTimeout(() => { toast.hidden = true }, 4500)
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

function datasetType(name) {
  const lower = name.toLowerCase()
  return lower.endsWith('.vsi') || lower.endsWith('.ets') ? 'vsi' : 'ome'
}

function render() {
  const visible = state.datasets.filter((dataset) => {
    const matchesFilter = state.filter === 'all' || dataset.type === state.filter
    return matchesFilter && dataset.name.toLowerCase().includes(state.query)
  })
  emptyState.hidden = state.datasets.length > 0
  grid.hidden = state.datasets.length === 0
  grid.replaceChildren(...visible.map((dataset) => {
    const card = document.createElement('article')
    card.className = 'dataset-card'
    const title = document.createElement('h3')
    title.textContent = dataset.name
    const details = document.createElement('p')
    details.textContent = `${formatBytes(dataset.size)} · Local source preserved`
    const badge = document.createElement('span')
    badge.className = 'badge'
    badge.textContent = dataset.batchState
    card.append(title, details, badge)
    return card
  }))
  queueStatus.textContent = `${state.datasets.length} staged`
}

async function csrfToken() {
  const response = await fetch('/api/session')
  if (!response.ok) throw new Error('Local session expired')
  return response.headers.get('X-Forge-CSRF')
}

async function stageBatch(files) {
  const csrf = await csrfToken()
  const response = await fetch('/api/batches', {
    method: 'POST',
    headers: {'Content-Type': 'application/json', 'X-Forge-CSRF': csrf},
    body: JSON.stringify({datasets: files.map((file) => file.name)}),
  })
  if (!response.ok) throw new Error('Could not stage the local batch')
  return response.json()
}

async function acceptFiles(files) {
  const supported = files.filter((file) => /\.(ome\.tiff?|vsi|ets)$/i.test(file.name))
  if (!supported.length) {
    notify('Choose an OME-TIFF or complete VSI / ETS dataset.')
    return
  }
  try {
    await stageBatch(supported)
    state.datasets.push(...supported.map((file) => ({
      name: file.name,
      size: file.size,
      type: datasetType(file.name),
      batchState: 'Staged for inspection',
    })))
    render()
    notify(`${supported.length} dataset${supported.length === 1 ? '' : 's'} staged locally.`)
  } catch (error) {
    notify(error.message)
  }
}

async function chooseDatasets() {
  if ('showOpenFilePicker' in window) {
    try {
      const handles = await window.showOpenFilePicker({
        multiple: true,
        types: [{
          description: 'Pathology datasets',
          accept: {
            'image/tiff': ['.ome.tif', '.ome.tiff'],
            'application/octet-stream': ['.vsi', '.ets'],
          },
        }],
      })
      await acceptFiles(await Promise.all(handles.map((handle) => handle.getFile())))
    } catch (error) {
      if (error.name !== 'AbortError') notify('The dataset picker could not be opened.')
    }
    return
  }
  picker.click()
}

function applyTheme() {
  if (state.theme === 'system') delete document.documentElement.dataset.theme
  else document.documentElement.dataset.theme = state.theme
  document.querySelector('[data-action="theme"]').textContent =
    `Theme: ${state.theme[0].toUpperCase()}${state.theme.slice(1)}`
}

document.querySelectorAll('[data-action="add-datasets"]').forEach((button) => {
  button.addEventListener('click', chooseDatasets)
})
picker.addEventListener('change', () => {
  acceptFiles(Array.from(picker.files))
  picker.value = ''
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
document.querySelector('[data-action="connect-viewer"]').addEventListener('click', () => {
  viewerDialog.showModal()
})
viewerDialog.addEventListener('close', () => {
  if (viewerDialog.returnValue === 'save') {
    notify('Viewer address saved for the upcoming credential-pairing slice.')
  }
})

applyTheme()
render()
