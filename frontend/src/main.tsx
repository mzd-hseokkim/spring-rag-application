import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@/index.css'
import { installLoadingSpinner } from '@/lib/loading-spinner'
import App from '@/App'

installLoadingSpinner()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
