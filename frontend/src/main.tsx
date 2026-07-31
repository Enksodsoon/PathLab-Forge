import '@pathlab/viewer-ui/theme.css'
import '@pathlab/viewer-ui/styles.css'
import './styles.css'

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { App } from './App'

const themeChoice = localStorage.getItem('pathlab-forge-theme') || 'system'
const darkTheme = themeChoice === 'dark'
  || (themeChoice === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
document.documentElement.dataset.theme = darkTheme ? 'dark' : 'light'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
